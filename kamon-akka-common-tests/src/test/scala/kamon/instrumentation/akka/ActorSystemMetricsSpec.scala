/* =========================================================================================
 * Copyright © 2013 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.instrumentation.akka

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import kamon.instrumentation.akka.ActorMetricsTestActor._
import kamon.testkit.{InstrumentInspection, MetricInspection}
import org.scalactic.TimesOnInt._
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._


class ActorSystemMetricsSpec extends TestKit(ActorSystem("ActorSystemMetricsSpec")) with WordSpecLike with MetricInspection.Syntax with InstrumentInspection.Syntax with Matchers
    with BeforeAndAfterAll with ImplicitSender with Eventually {

  val systemMetrics = AkkaMetrics.forSystem(system.name)

  "the Actor System metrics" should {
    "record active actor counts" in {
      eventually(timeout(5 seconds)) {
        val activeActors = systemMetrics.activeActors.distribution()

        // This establishes a baseline on actor counts for the rest of the test.
        activeActors.count should be > 0L
        activeActors.min shouldBe 6L
        activeActors.max shouldBe 6L
      }

      val actors = (1 to 10).map(id => watch(system.actorOf(Props[ActorMetricsTestActor], s"just-some-actor-$id")))
      val parent = watch(system.actorOf(Props[SecondLevelGrouping], "just-some-parent-actor"))

      1000 times {
        actors.foreach(_ ! Discard)
        parent ! Discard
      }

      eventually(timeout(5 seconds)) {
        val activeActors = systemMetrics.activeActors.distribution()
        activeActors.count should be > 0L
        activeActors.min shouldBe 27L
        activeActors.max shouldBe 27L
      }

      actors.foreach(system.stop)
      system.stop(parent)

      eventually(timeout(5 seconds)) {
        val activeActors = systemMetrics.activeActors.distribution()
        activeActors.count should be > 0L
        activeActors.min shouldBe 6L
        activeActors.max shouldBe 6L
      }
    }

    "record dead letters" in {
      val doaActor = system.actorOf(Props[ActorMetricsTestActor], "doa")
      val deathWatcher = TestProbe()
      systemMetrics.deadLetters.value(true)
      deathWatcher.watch(doaActor)
      doaActor ! PoisonPill
      deathWatcher.expectTerminated(doaActor)

      7 times { doaActor ! "deadonarrival" }

      eventually {
        systemMetrics.deadLetters.value(false).toInt should be(7)
      }
    }

    "record unhandled messages" in {
      val unhandled = system.actorOf(Props[ActorMetricsTestActor], "unhandled")
      10 times { unhandled ! "CantHandleStrings" }

      eventually {
        systemMetrics.unhandledMessages.value(false).toInt should be(10)
      }
    }

    "record processed messages counts" in {
      systemMetrics.processedMessagesByTracked.value(true)
      systemMetrics.processedMessagesByNonTracked.value(true)
      systemMetrics.processedMessagesByNonTracked.value(false) should be(0)

      val tracked = system.actorOf(Props[ActorMetricsTestActor], "tracked-actor-counts")
      val nonTracked = system.actorOf(Props[ActorMetricsTestActor], "non-tracked-actor-counts")

      (1 to 10).foreach(_ => tracked ! Discard)
      (1 to 15).foreach(_ => nonTracked ! Discard)

      eventually(timeout(3 second)) {
        systemMetrics.processedMessagesByTracked.value(false) should be >= (10L)
        systemMetrics.processedMessagesByNonTracked.value(false) should be >= (15L)
      }
    }
  }

  override protected def afterAll(): Unit = shutdown()
}
