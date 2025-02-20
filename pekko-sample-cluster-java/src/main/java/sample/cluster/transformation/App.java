package sample.cluster.transformation;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.typed.Cluster;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class App {

  private static class RootBehavior {
    static Behavior<Void> create() {
      return Behaviors.setup(context -> {
        Cluster cluster = Cluster.get(context.getSystem());

        if (cluster.selfMember().hasRole("backend")) {
          int workersPerNode = context.getSystem().settings().config().getInt("transformation.workers-per-node");
          for (int i = 0; i < workersPerNode; i++) {
            context.spawn(Worker.create(), "Worker" + i);
          }
        }
        if (cluster.selfMember().hasRole("frontend")) {
          context.spawn(Frontend.create(), "Frontend");
        }

        return Behaviors.empty();
      });
    }
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      startup("backend", 17356);
      startup("backend", 17357);
      startup("frontend", 0);
      startup("frontend", 0);
      startup("frontend", 0);
    } else {
      if (args.length != 2)
        throw new IllegalArgumentException("Usage: role port");
      startup(args[0], Integer.parseInt(args[1]));
    }
  }

  private static void startup(String role, int port) {

    // Override the configuration of the port
    Map<String, Object> overrides = new HashMap<>();
    overrides.put("org.apache.pekko.remote.artery.canonical.port", port);
    overrides.put("org.apache.pekko.cluster.roles", Collections.singletonList(role));

    Config config = ConfigFactory.parseMap(overrides)
        .withFallback(ConfigFactory.load("transformation"));

    ActorSystem<Void> system = ActorSystem.create(RootBehavior.create(), "ClusterSystem", config);
  }
}
