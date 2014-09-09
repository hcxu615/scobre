package org.ccfea.tickdata.event

import java.util.Date
import org.ccfea.tickdata.order.AbstractOrder

/**
 * A new order has been submitted to the exchange.
 *
 * (C) Steve Phelps 2013
 */
case class OrderSubmittedEvent(val timeStamp: Date, val messageSequenceNumber: Long,
                          val tiCode: String, val order: AbstractOrder) extends OrderReplayEvent