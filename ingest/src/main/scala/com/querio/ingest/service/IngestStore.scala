/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.querio.ingest.service

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

import java.util.concurrent.atomic.AtomicInteger
import java.util.Properties

import akka.dispatch.Future

import blueeyes.json.JPath
import blueeyes.json.JsonAST._

import org.scalacheck.Gen

import kafka.producer._

import com.querio.ingest.api._
import com.querio.ingest.util.ArbitraryJValue


//object QuickEventStoreDemo extends ArbitraryJValue {
//  def main(args: Array[String]) {
//    
//    val eventSenderMap = Map() + (MailboxAddress(0L) -> new EchoEventSender())
//    
//    val defaultAddresses = List(MailboxAddress(0))
//    
//    val store = new DefaultEventStore(0,
//                                      new ConstantEventRouter(defaultAddresses),
//                                      new MappedEventSenders(eventSenderMap))
//    
//    val jv1 = JObject(List(JField("foo", JString("bar")), JField("array", JArray(List(JInt(1), JInt(2)))), JField("sub", JObject(List(JField("age", JInt(100)), JField("height", JInt(63)))))))
//    val jv2 = JArray(List(JInt(1), JInt(2)))
//    val jv3 = JInt(1)
//    
//    val jvs = jv1 :: jv2 :: jv3 :: Nil
//    
//    val path = "/foo/bar/event"
//
//    jvs.foreach(jv => store.save(Event(path, "token", jv)))    
//  }
//}

// todo
//
// - write unit test against collecting senders
// - test failure semantics against senders
// - write simple kafka backed senders
// - producer id generation taken from zookeeper
//

trait EventStore {
  def save(event: Event): Future[Unit]
}

class DefaultEventStore(producerId: Int, router: EventRouter, senders: MessageSenders) extends EventStore {
  private val nextEventId = new AtomicInteger
  
  def save(event: Event): Future[Unit] = {
    val eventId = nextEventId.incrementAndGet
    Future.sequence(router.route(event).map(address => {
      senders.find(address).map(sender => {
        sender.send(EventMessage(producerId, eventId, event))
      }).getOrElse(Future(()))
    })).map(_ => ())    
  }  
}

trait EventRouter {
  def route(event: Event): List[MailboxAddress]
}

class ConstantEventRouter(addresses: List[MailboxAddress]) extends EventRouter {
  def route(event: Event): List[MailboxAddress] = addresses
}

trait MessageSender {
  def send(msg: IngestMessage): Future[Unit]
}

class EchoMessageSender extends MessageSender {
  def send(msg: IngestMessage) = {
    Future(println("Sending: " + msg))
  }
}

class CollectingMessageSender extends MessageSender {

  val events = ListBuffer[IngestMessage]()

  def send(msg: IngestMessage) = {
    events += msg
    Future(())
  }
}

class KafkaMessageSender(topic: String, config: Properties) extends MessageSender {
 
  val producer = new Producer[String, IngestMessage](new ProducerConfig(config))

  def send(msg: IngestMessage) = {
    Future {
      val data = new ProducerData[String, IngestMessage](topic, msg)
      try {
        producer.send(data)
      } catch {
        case x => x.printStackTrace(System.out); throw x
      }
    }
  }
}

trait MessageSenders {
  def find(outboxId: MailboxAddress): Option[MessageSender]
}

class MappedMessageSenders(map: Map[MailboxAddress, MessageSender]) extends MessageSenders {
  def find(address: MailboxAddress) = {
    map.get(address)
  }
}
