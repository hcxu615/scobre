package org.ccfea.tickdata.event

import java.util.Date

import org.ccfea.tickdata.order.{TradeDirection, AbstractOrder}

/**
 * (C) Steve Phelps 2014
 */
case class OrderRevisedEvent(timeStamp: Date, messageSequenceNumber: Long,
                                  tiCode: String, order: AbstractOrder,
                                  newPrice: BigDecimal, newVolume: Long,
                                  newDirection: TradeDirection.Value) extends OrderEvent

