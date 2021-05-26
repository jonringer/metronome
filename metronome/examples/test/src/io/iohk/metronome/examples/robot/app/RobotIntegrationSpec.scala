package io.iohk.metronome.examples.robot.app

import cats.implicits._
import cats.effect.Resource
import io.iohk.metronome.crypto.ECKeyPair
import io.iohk.metronome.hotstuff.consensus.basic.Phase
import io.iohk.metronome.hotstuff.service.tracing.ConsensusEvent
import io.iohk.metronome.examples.robot.RobotAgreement
import io.iohk.metronome.examples.robot.app.config.{
  RobotConfig,
  RobotConfigParser,
  RobotOptions
}
import io.iohk.metronome.logging.HybridLogObject
import java.nio.file.Files
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.compatible.Assertion
import scala.concurrent.duration._
import org.scalatest.matchers.should.Matchers
import org.scalatest.Inspectors

/** Set up an in-memory federation with simulated network stack and elapsed time. */
class RobotIntegrationSpec extends AnyFlatSpec with Matchers with Inspectors {
  import RobotIntegrationSpec._
  import RobotTestConnectionManager.{Delay, Loss}

  def test(fixture: Fixture): Assertion = {
    implicit val scheduler = fixture.scheduler

    // Without an extra delay, the `TestScheduler` executes tasks immediately.
    val fut = fixture.resources.use { envs =>
      fixture.test(envs).delayExecution(0.second)
    }.runToFuture

    scheduler.tick(fixture.duration)

    fut.value.getOrElse(sys.error("The test hasn't finished")).get
  }

  // Use this to debug tests.
  def printLogs(logs: List[Seq[HybridLogObject]]): Unit = {
    logs.zipWithIndex
      .flatMap { case (logs, i) =>
        logs.map(log => (i, log))
      }
      .sortBy(_._2.timestamp)
      .foreach { case (i, log) =>
        println(s"node-$i: ${log.show}")
      }
  }

  behavior of "RobotAgreement"

  it should "compose components that can run and stay in sync" in test {
    // This is a happy scenario, all nodes starting at the same time and
    // running flawlessly, so we should see consensus very quickly.
    new Fixture(
      1.minutes,
      networkDelay = Delay(min = 50.millis, max = 1.second),
      networkLoss = Loss(0.01)
    ) {
      override def test(envs: List[RobotTestComposition.Env]) =
        for {
          _    <- Task.sleep(duration - 5.seconds)
          logs <- envs.traverse(_.logTracer.getLogs)

          quourumCounts <- envs.traverse(
            _.consensusEventTracer
              .count[ConsensusEvent.Quorum[RobotAgreement]]
          )
          blockCounts <- envs.traverse(
            _.consensusEventTracer
              .count[ConsensusEvent.BlockExecuted[RobotAgreement]]
          )

          lastCommittedBlockHashes <- envs
            .traverse { env =>
              env.consensusEventTracer.getEvents.map { events =>
                events.reverse.collectFirst {
                  case ConsensusEvent.Quorum(qc) if qc.phase == Phase.Commit =>
                    qc.blockHash
                }
              }
            }
            .map(_.flatten)

          lastExecutedBlockHashes <- envs.traverse { env =>
            env.storages.storeRunner.runReadOnly {
              env.storages.viewStateStorage.getLastExecutedBlockHash
            }
          }
        } yield {
          // printLogs(logs)
          all(quourumCounts) should be > 0
          all(blockCounts) should be > 0
          // Check that consensus is reasonably close on nodes.
          lastExecutedBlockHashes.distinct.size should be <= 2
          lastCommittedBlockHashes.distinct.size should be <= 2
          // Hopefully someone has executed the last commit as well.
          forAtLeast(
            1,
            lastCommittedBlockHashes
          ) { lastCommittedBlockHash =>
            lastExecutedBlockHashes.contains(
              lastCommittedBlockHash
            ) shouldBe true
          }
        }
    }
  }
}

object RobotIntegrationSpec {

  import RobotTestConnectionManager.{Delay, Loss}

  abstract class Fixture(
      val duration: FiniteDuration,
      networkDelay: Delay = Delay.Zero,
      networkLoss: Loss = Loss.Zero
  ) extends RobotComposition {

    /** Override to implement the test. */
    def test(envs: List[RobotTestComposition.Env]): Task[Assertion]

    val scheduler = TestScheduler()

    val config: Resource[Task, RobotConfig] =
      for {
        defaultConfig <- Resource.liftF {
          Task.fromEither {
            RobotConfigParser.parse.left.map(err =>
              new IllegalArgumentException(err.toString)
            )
          }
        }
        // Use 5 nodes in integration testing. Just generate new keys,
        // ignore what's in the default configuration.
        nodeCount = 5
        rnd       = new java.security.SecureRandom()
        keys      = List.fill(nodeCount)(ECKeyPair.generate(rnd))

        tmpdir <- Resource.liftF(Task {
          val tmp = Files.createTempDirectory("robot-testdb")
          tmp.toFile.deleteOnExit()
          tmp
        })

        config = defaultConfig.copy(
          network = defaultConfig.network.copy(
            nodes = keys.zipWithIndex.map { case (pair, i) =>
              RobotConfig.Node(
                host = "localhost",
                port = 40000 + i,
                publicKey = pair.pub,
                privateKey = pair.prv
              )
            }
          ),
          db = defaultConfig.db.copy(
            path = tmpdir
          )
        )
      } yield config

    val resources =
      for {
        config <- config
        dispatcher <- Resource.liftF(
          RobotTestConnectionManager.Dispatcher(networkDelay, networkLoss)
        )
        nodeEnvs <- (0 until config.network.nodes.size).toList.map { i =>
          val opts = RobotOptions(nodeIndex = i)
          val comp = makeComposition(scheduler, dispatcher)
          comp.composeEnv(opts, config)
        }.sequence
      } yield nodeEnvs

    def makeComposition(
        scheduler: TestScheduler,
        dispatcher: RobotTestConnectionManager.Dispatcher
    ) =
      new RobotTestComposition(scheduler, dispatcher)
  }
}
