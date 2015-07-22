package org.ccfea.tickdata.storage.shuffled

import org.ccfea.tickdata.event._
import org.ccfea.tickdata.order.{LimitOrder, OrderWithVolume}

/**
 * Created by sphelps on 21/07/15.
 */
class RandomPermutationOfVolume(source: Seq[TickDataEvent], proportion: Double, windowSize: Int = 1)
    extends RandomPermutation[Option[Long]](source, proportion, windowSize,

  getter = (i, ticks) => {
    ticks(i) match {
      case OrderEvent(_, _, _, OrderWithVolume(_, volume, _, _)) =>
          Some(volume)
      case _ => None
    }
  },

  setter = (i, x, ticks) => {
    x match {
      case Some(newVolume) =>
        ticks (i) match {
          case OrderSubmittedEvent(timeStamp, messageSequenceNumber, tiCode,
                            LimitOrder(orderCode, aggregateSize, tradeDirection, price, trader)) =>
              val revisedOrder =
                new LimitOrder (orderCode, newVolume, tradeDirection, price, trader)
              ticks(i) = new OrderSubmittedEvent(timeStamp, messageSequenceNumber, tiCode, revisedOrder)
          case _ =>
            // Do nothing, TODO check with IMON
        }
      case _ =>
        //no action taken, TODO check with Imon
    }
  }

)
