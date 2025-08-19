package com.gfde.order;

import com.gfde.kernel.GfdeKernelClient;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Instant;
import java.util.*;

@Configuration
public class OrderGraphConfig {
  @Bean GfdeKernelClient kernel() { return GfdeKernelClient.inMemory(); }

  @Bean RuntimeWiring wiring(GfdeKernelClient kernel) {
    return RuntimeWiring.newRuntimeWiring()
      .type(TypeRuntimeWiring.newTypeWiring("Query")
        .dataFetcher("ordersByUser", env ->
          kernel.scan("Order", "userId", env.getArgument("userId"), env.getArgumentOrDefault("first", 10)))
      )
      .type(TypeRuntimeWiring.newTypeWiring("Mutation")
        .dataFetcher("createOrder", env -> {
          Map<String,Object> o = new HashMap<>();
          o.put("id", env.getArgument("id"));
          o.put("userId", env.getArgument("userId"));
          o.put("total", env.getArgument("total"));
          o.put("createdAt", Instant.now().toString());
          kernel.put("Order", (String)o.get("id"), o);
          return o;
        })
      )
      .build();
  }
}