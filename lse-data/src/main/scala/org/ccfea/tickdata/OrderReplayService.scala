package org.ccfea.tickdata

import java.util.Date
import java.{lang, util}

import org.ccfea.tickdata.order.{OrderWithVolume, LimitOrder}
import org.ccfea.tickdata.order.offset.{OppositeSideOffsetOrder, SameSideOffsetOrder, MidPriceOffsetOrder, Offsetting}

import scala.collection.parallel

import grizzled.slf4j.Logger
import org.apache.thrift.server.TThreadPoolServer
import org.apache.thrift.transport.TServerSocket
import org.ccfea.tickdata.collector.MultivariateTimeSeriesCollector
import org.ccfea.tickdata.conf.{BuildInfo, ServerConf}

import org.ccfea.tickdata.event.{OrderEvent, TickDataEvent}
import org.ccfea.tickdata.simulator.{Quote, ClearingMarketState, MarketState}
import org.ccfea.tickdata.storage.hbase.HBaseRetriever
import org.ccfea.tickdata.storage.shuffled.{OffsettedTicks, IntraWindowRandomPermutation, RandomPermutation}
import org.ccfea.tickdata.storage.thrift.MultivariateThriftCollator
import org.ccfea.tickdata.thrift.OrderReplay

import collection.JavaConversions._

/**
 * A server that provides order-replay simulation results over the network.
 * It uses Apache Thrift so that clients can easily be written in other languages.
 *
 * (C) Steve Phelps 2014
 */
object OrderReplayService extends ReplayApplication {

  class Replayer(val eventSource: Iterable[TickDataEvent],
                 val dataCollectors: Map[String, MarketState => Option[AnyVal]],
                  val marketState: MarketState)
    extends MultivariateTimeSeriesCollector with MultivariateThriftCollator

  var tickCache = Map[(String, Offsetting.Value, Option[(Long, Long)]), Seq[TickDataEvent]]()

  val logger = Logger("org.ccmainfea.tickdata.OrderReplayService")

  /**
   *    Use reflection to find the method to retrieve the  data for each variable (a function of MarketState).
   *
   * @param variables  The variables to collect from the simulation.
   * @return            a map of variables and methods, i.e. the collectors for the simulation.
   */
  def collectors(variables: java.util.List[String]) = {
    def variableToMethod(variable: String): MarketState => Option[AnyVal] =
      classOf[MarketState].getMethod(variable) invoke _
    for (variable <- variables) yield (variable, variableToMethod(variable))
  }

  def getOffsettedTicks(ticks: Seq[TickDataEvent], offsetting: Offsetting.Value)(implicit conf: ServerConf) = {
    val marketState = newMarketState
    offsetting match {
      case Offsetting.NoOffsetting =>
        ticks
      case Offsetting.MidPrice =>
        new OffsettedTicks(marketState, ticks,
                            (limitOrder: LimitOrder, quote: Quote) => new MidPriceOffsetOrder(limitOrder, quote))
      case Offsetting.SameSide =>
        new OffsettedTicks(marketState, ticks,
                            (limitOrder: LimitOrder, quote: Quote) => new SameSideOffsetOrder(limitOrder, quote))
      case Offsetting.OppositeSide =>
        new OffsettedTicks(marketState, ticks,
                              (limitOrder: LimitOrder, quote: Quote) => new OppositeSideOffsetOrder(limitOrder, quote))
    }
  }

  def getShuffledData(assetId: String,
                          proportionShuffling: Double,
                          windowSize: Int, intraWindow: Boolean,
                          offsetting: Offsetting.Value,
                          dateRange: Option[(Long, Long)])(implicit conf: ServerConf): RandomPermutation[TickDataEvent] = {
    val ticks = if (tickCache.contains((assetId, offsetting))) {
      tickCache((assetId, offsetting, dateRange))
    } else {
      val start = dateRange match {
        case None => None
        case Some((t0, t1)) => Some(new Date(t0))
      }
      val end = dateRange match {
        case None => None
        case Some((t0, t1)) => Some(new Date(t1))
      }
      val originalData = new HBaseRetriever(selectedAsset = assetId, startDate = start, endDate = end).toList
      val offsettedTicks = getOffsettedTicks(originalData, offsetting).toList
      tickCache += ((assetId, offsetting, dateRange) -> offsettedTicks)
      offsettedTicks
    }
    if (intraWindow)
      new IntraWindowRandomPermutation[TickDataEvent](ticks, proportionShuffling, windowSize,
                                            getter = (i, ticks) => ticks(i),
                                              setter = (a, b, ticks) => ticks(a) = b)

    else
      new RandomPermutation[TickDataEvent](ticks, proportionShuffling, windowSize,
                                            getter = (i, ticks) => ticks(i),
                                              setter = (a, b, ticks) => ticks(a) = b)
  }

  def executeShuffledReplay(assetId: String, variables: util.List[String],
                                    proportionShuffling: Double, windowSize: Int, intraWindow: Boolean,
                                      offsetting: Int, dateRange: Option[(Long, Long)])(implicit conf: ServerConf):
                                                util.List[util.Map[String, java.lang.Double]] = {
    logger.info("Shuffled replay for " + assetId + " with windowSize " + windowSize +
                      ", offsetting " + offsetting + " and percentage " + proportionShuffling)
    dateRange match {
      case Some((t0, t1)) =>
        logger.info("between " + new Date(t0) + " and " + new Date(t1))
      case None =>
    }
    logger.info("Starting simulation... ")
    val marketState = newMarketState(conf)
    val shuffledTicks =
      getShuffledData(assetId, proportionShuffling, windowSize, intraWindow, Offsetting(offsetting), dateRange)
    val replayer =
      new Replayer(shuffledTicks, dataCollectors = Map() ++ collectors(variables), marketState)
    replayer.run()
    logger.info("done.")
    replayer.result
  }

  def main(args: Array[String]): Unit = {

    implicit val conf = new ServerConf(args)
    val port: Int = conf.port()

    val processor = new org.ccfea.tickdata.thrift.OrderReplay.Processor(new OrderReplay.Iface {

      override def replay(assetId: String, variables: java.util.List[String],
                            startDate: String,
                            endDate: String): java.util.List[java.util.Map[String,java.lang.Double]] = {
        logger.info("Using data for " + assetId + " between " + startDate + " and " + endDate)
        logger.info("Starting simulation... ")
        val marketState = newMarketState(conf)
        val ticks =
          new HBaseRetriever(selectedAsset = assetId,
                              startDate = parseDate(Some(startDate)), endDate = parseDate(Some(endDate)))
        val replayer = new Replayer(ticks, dataCollectors = Map() ++ collectors(variables), marketState)
        replayer.run()
        logger.info("done.")
        replayer.result
      }

      override def shuffledReplayDateRange(assetId: String, variables: util.List[String],
                                    proportionShuffling: Double, windowSize: Int, intraWindow: Boolean,
                                      offsetting: Int, startDateTime: Long, endDateTime: Long):
                                                util.List[util.Map[String, java.lang.Double]] = {
        executeShuffledReplay(assetId, variables, proportionShuffling, windowSize, intraWindow, offsetting,
                                Some((startDateTime, endDateTime)))
      }

      override def shuffledReplay(assetId: String, variables: util.List[String], proportionShuffling: Double, windowSize: Int, intraWindow: Boolean, offsetting: Int): util.List[util.Map[String, lang.Double]] = {
        executeShuffledReplay(assetId, variables, proportionShuffling, windowSize, intraWindow, offsetting, None)
      }

    })

    val serverTransport = new TServerSocket(port)
    val server = new TThreadPoolServer(new TThreadPoolServer.Args(serverTransport).processor(processor))

    logger.info("CCFEA order-replay server version " + BuildInfo.version)
    logger.info("Server running on port " + port + "... ")
    server.serve()
    logger.info("Server terminated.")
  }

}
