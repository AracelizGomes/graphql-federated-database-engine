package com.gfde.router;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import java.util.Map;

@RestController
@RequestMapping("/graphql")
public class GatewayController {

  private final RestClient user = RestClient.create(System.getProperty("gfde.user", "http://localhost:8081"));
  private final RestClient order = RestClient.create(System.getProperty("gfde.order","http://localhost:8082"));

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String,Object> proxy(@RequestBody Map<String,Object> body) {
    // MVP: naive recognizer — split query into user then order call
    String query = (String) body.get("query");
    if (query.contains("orders(")) {
      // 1) get user
      Map<String,Object> uResp = user.post().uri("/graphql").body(body).retrieve().body(Map.class);
      Map<String,Object> uData = (Map<String,Object>)uResp.get("data");
      Map<String,Object> userObj = (Map<String,Object>)uData.values().stream().findFirst().orElse(Map.of());
      String userId = (String)userObj.get("id");
      // 2) fetch orders by user
      String orderQuery = """
        {"query":"query($uid:ID!){ ordersByUser(userId:$uid, first:5){ id total createdAt } }","variables":{"uid":"%s"}}
      """.formatted(userId);
      Map<String,Object> oResp = order.post().uri("/graphql").body(orderQuery).retrieve().body(Map.class);
      // 3) stitch
      ((Map<String,Object>) userObj).put("orders", ((Map<String,Object>)oResp.get("data")).get("ordersByUser"));
      return Map.of("data", uData);
    }
    // pass-through (no join)
    return user.post().uri("/graphql").body(body).retrieve().body(Map.class);
  }
}