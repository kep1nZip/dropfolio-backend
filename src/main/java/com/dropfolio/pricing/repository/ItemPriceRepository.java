package com.dropfolio.pricing.repository;

import com.dropfolio.pricing.entity.ItemPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ItemPriceRepository extends JpaRepository<ItemPrice, Long> {

    /**
     * "SELECT TOP 1 item_prices WHERE item_id = :id ORDER BY fetched_at DESC" —
     * TECHNICAL_SPEC.md §6.2 SQL-fallback step. Backed by
     * {@code IX_item_prices_item_fetched (item_id, fetched_at DESC)} — ERD.md §2.6.
     */
    Optional<ItemPrice> findTopByItemIdOrderByFetchedAtDesc(Long itemId);
}
