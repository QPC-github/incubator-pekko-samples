package sample.cluster.stats

import org.apache.pekko.actor.typed.receptionist.Receptionist
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.Routers
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.typed.Cluster
import org.apache.pekko.cluster.typed.ClusterSingleton
import org.apache.pekko.cluster.typed.ClusterSingletonSettings
import org.apache.pekko.cluster.typed.SingletonActor
import com.typesafe.config.ConfigFactory

object AppOneMaster {

  val WorkerServiceKey = ServiceKey[StatsWorker.Process]("Worker")

  object RootBehavior {
    def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>
      val cluster = Cluster(ctx.system)

      val singletonSettings = ClusterSingletonSettings(ctx.system)
        .withRole("compute")
      val serviceSingleton = SingletonActor(
        Behaviors.setup[StatsService.Command] { ctx =>
          // the service singleton accesses available workers through a group router
          val workersRouter =
            ctx.spawn(
              Routers
                .group(WorkerServiceKey)
                // the worker has a per word cache, so send the same word to the same worker
                .withConsistentHashingRouting(1, _.word),
              "WorkersRouter"
            )

          StatsService(workersRouter)
        },
        "StatsService"
      ).withStopMessage(StatsService.Stop)
        .withSettings(singletonSettings)
      val serviceProxy = ClusterSingleton(ctx.system).init(serviceSingleton)

      if (cluster.selfMember.hasRole("compute")) {
        // on every compute node N local workers, which a cluster singleton stats service delegates work to
        val numberOfWorkers =
          ctx.system.settings.config.getInt("stats-service.workers-per-node")
        ctx.log.info("Starting {} workers", numberOfWorkers)
        (0 to numberOfWorkers).foreach { n =>
          val worker = ctx.spawn(StatsWorker(), s"StatsWorker$n")
          ctx.system.receptionist ! Receptionist
            .Register(WorkerServiceKey, worker)
        }
      }
      if (cluster.selfMember.hasRole("client")) {
        ctx.spawn(StatsClient(serviceProxy), "Client")
      }
      Behaviors.empty
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      startup("compute", 17356)
      startup("compute", 17357)
      startup("compute", 0)
      startup("client", 0)
    } else {
      require(args.size == 2, "Usage: role port")
      startup(args(0), args(1).toInt)
    }
  }

  def startup(role: String, port: Int): Unit = {
    // Override the configuration of the port when specified as program argument
    val config = ConfigFactory
      .parseString(s"""
      org.apache.pekko.remote.artery.canonical.port=$port
      org.apache.pekko.cluster.roles = [$role]
      """)
      .withFallback(ConfigFactory.load("stats"))

    ActorSystem[Nothing](RootBehavior(), "ClusterSystem", config)
  }

}
