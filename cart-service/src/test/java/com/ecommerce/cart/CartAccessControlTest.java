package com.ecommerce.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shoppers sign in with platform tokens (no tenant); the cart must accept them and never trust X-User-Id. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "SERVICE_TO_SERVICE_TOKEN=cart-test-service-token")
class CartAccessControlTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Value("${jwt.secret}") String secret;

    private String platformToken(long userId, long ttlMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", userId);
        claims.put("role", "USER");
        return "Bearer " + Jwts.builder().setClaims(claims).setSubject("shopper" + userId + "@test")
                .setIssuer("universal-commerce").setAudience("commerce-api")
                .setExpiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()), SignatureAlgorithm.HS256).compact();
    }

    private String item(long productId) throws Exception {
        return mapper.writeValueAsString(Map.of("productId", productId, "sku", "SKU-" + productId,
                "productName", "Item " + productId, "unitPrice", 10, "quantity", 1));
    }

    private int itemCount(String json) throws Exception {
        JsonNode items = mapper.readTree(json).path("items");
        return items.isArray() ? items.size() : 0;
    }

    @Test
    void signedInShopperUsesOwnCartAndOthersCannotSeeIt() throws Exception {
        String alice = platformToken(31L, 600_000);
        String bob = platformToken(32L, 600_000);
        mvc.perform(post("/api/cart/items").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(item(501)))
                .andExpect(status().isOk());

        String aliceCart = mvc.perform(get("/api/cart").header("Authorization", alice))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(itemCount(aliceCart)).isEqualTo(1);

        String bobCart = mvc.perform(get("/api/cart").header("Authorization", bob))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(itemCount(bobCart)).isZero();

        // A spoofed X-User-Id without a token is a guest session, not Alice.
        String spoofed = mvc.perform(get("/api/cart").header("X-User-Id", 31L).header("X-Cart-Session", "sess_spoof01"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(itemCount(spoofed)).isZero();
    }

    @Test
    void invalidOrExpiredTokensAreRejected() throws Exception {
        mvc.perform(get("/api/cart").header("Authorization", platformToken(33L, -1_000)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cart").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void convertNeedsTheServiceTokenOrTheOwner() throws Exception {
        mvc.perform(post("/api/cart/convert").param("userId", "34")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cart/convert").param("userId", "34").header("X-Service-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cart/convert").param("userId", "34").header("Authorization", platformToken(35L, 600_000)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cart/convert").param("userId", "34").header("Authorization", platformToken(34L, 600_000)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cart/convert").param("userId", "34").header("X-Service-Token", "cart-test-service-token"))
                .andExpect(status().isOk());
    }
}
