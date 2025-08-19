package com.gfde.user;
import com.gfde.kernel.GfdeKernelClient;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;

@Configuration
public class UserGraphConfig {
  @Bean GfdeKernelClient kernel() { return GfdeKernelClient.inMemory(); }

  @Bean RuntimeWiring wiring(GfdeKernelClient kernel) {
    return RuntimeWiring.newRuntimeWiring()
      .type(TypeRuntimeWiring.newTypeWiring("Query")
        .dataFetcher("userById", env -> kernel.get("User", env.getArgument("id")).orElse(null))
        .dataFetcher("userByEmail", env -> {
          var list = kernel.scan("User", "email", env.getArgument("email"), 1);
          return list.isEmpty()? null : list.getFirst();
        })
      )
      .type(TypeRuntimeWiring.newTypeWiring("Mutation")
        .dataFetcher("upsertUser", env -> {
          Map<String,Object> u = new HashMap<>();
          u.put("id", env.getArgument("id"));
          u.put("email", env.getArgument("email"));
          u.put("name", env.getArgument("name"));
          kernel.put("User", (String)u.get("id"), u);
          return u;
        })
      )
      .build();
  }
}