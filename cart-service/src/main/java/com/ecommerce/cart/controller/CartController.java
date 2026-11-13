package com.ecommerce.cart.controller;

import com.ecommerce.cart.dto.*;
import com.ecommerce.cart.security.CartPrincipal;
import com.ecommerce.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Customer Cart", description = "Endpoints for managing customer and guest shopping carts, sync, and validation")
public class CartController {

    private final CartService cartService;

    @org.springframework.beans.factory.annotation.Value("${SERVICE_TO_SERVICE_TOKEN:}")
    private String serviceToken;

    /** The signed-in shopper from the verified token, or null for a guest (session) cart. X-User-Id is never trusted. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CartPrincipal p ? p.userId() : null;
    }

    @Operation(summary = "Get Shopping Cart", description = "Retrieves active shopping cart for authenticated user or guest session.")
    @GetMapping
    public ResponseEntity<CartDto> getCart(
            @RequestHeader(value = "X-Cart-Session", required = false) String headerSession,
            @RequestParam(value = "sessionToken", required = false) String querySession) {
        String sessionToken = headerSession != null ? headerSession : querySession;
        return ResponseEntity.ok(cartService.getCart(currentUserId(), sessionToken));
    }

    @Operation(summary = "Add Item to Cart", description = "Adds a catalog product with quantity to the customer or guest cart.")
    @PostMapping("/items")
    public ResponseEntity<CartDto> addItem(
            @RequestHeader(value = "X-Cart-Session", required = false) String headerSession,
            @RequestParam(value = "sessionToken", required = false) String querySession,
            @Valid @RequestBody AddToCartRequest request) {
        String sessionToken = headerSession != null ? headerSession : querySession;
        return ResponseEntity.ok(cartService.addItem(currentUserId(), sessionToken, request));
    }

    @Operation(summary = "Update Cart Item Quantity", description = "Updates quantity of a specific line item in the cart.")
    @PutMapping("/items/{itemId}")
    public ResponseEntity<CartDto> updateItemQuantity(
            @RequestHeader(value = "X-Cart-Session", required = false) String headerSession,
            @RequestParam(value = "sessionToken", required = false) String querySession,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateItemQuantityRequest request) {
        String sessionToken = headerSession != null ? headerSession : querySession;
        return ResponseEntity.ok(cartService.updateItemQuantity(currentUserId(), sessionToken, itemId, request.getQuantity()));
    }

    @Operation(summary = "Remove Item from Cart", description = "Deletes a specific line item from the cart.")
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<CartDto> removeItem(
            @RequestHeader(value = "X-Cart-Session", required = false) String headerSession,
            @RequestParam(value = "sessionToken", required = false) String querySession,
            @PathVariable Long itemId) {
        String sessionToken = headerSession != null ? headerSession : querySession;
        return ResponseEntity.ok(cartService.removeItem(currentUserId(), sessionToken, itemId));
    }

    @Operation(summary = "Clear Entire Cart", description = "Removes all items from the current active cart.")
    @DeleteMapping
    public ResponseEntity<CartDto> clearCart(
            @RequestHeader(value = "X-Cart-Session", required = false) String headerSession,
            @RequestParam(value = "sessionToken", required = false) String querySession) {
        String sessionToken = headerSession != null ? headerSession : querySession;
        return ResponseEntity.ok(cartService.clearCart(currentUserId(), sessionToken));
    }

    @Operation(summary = "Synchronize Cart", description = "Bulk merges or replaces items from client local storage / guest session.")
    @PostMapping("/sync")
    public ResponseEntity<CartDto> syncCart(
            @RequestHeader(value = "X-Cart-Session", required = false) String headerSession,
            @RequestParam(value = "sessionToken", required = false) String querySession,
            @RequestBody CartSyncRequest request) {
        String sessionToken = headerSession != null ? headerSession : (querySession != null ? querySession : request.getSessionToken());
        return ResponseEntity.ok(cartService.syncCart(currentUserId(), sessionToken, request.getItems()));
    }

    @Operation(summary = "Validate Cart Availability", description = "Validates items against inventory quantities and product pricing prior to checkout.")
    @GetMapping("/validate")
    public ResponseEntity<CartValidationResultDto> validateCart(
            @RequestHeader(value = "X-Cart-Session", required = false) String headerSession,
            @RequestParam(value = "sessionToken", required = false) String querySession) {
        String sessionToken = headerSession != null ? headerSession : querySession;
        return ResponseEntity.ok(cartService.validateCart(currentUserId(), sessionToken));
    }

    @Operation(summary = "Convert Cart", description = "Internal endpoint called upon successful checkout to mark cart as converted.")
    @PostMapping("/convert")
    public ResponseEntity<Void> convertCart(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-Service-Token", required = false) String suppliedServiceToken) {
        boolean trustedService = suppliedServiceToken != null && serviceToken != null && !serviceToken.isBlank()
                && java.security.MessageDigest.isEqual(suppliedServiceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        serviceToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Long shopper = currentUserId();
        Long userId = trustedService ? queryUserId : shopper;
        if (userId == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        if (!trustedService && queryUserId != null && !queryUserId.equals(shopper)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        cartService.markCartConverted(userId);
        return ResponseEntity.ok().build();
    }
}
