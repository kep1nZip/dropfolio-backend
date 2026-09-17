package com.dropfolio.item.repository;

import com.dropfolio.item.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {

    boolean existsByMarketHashName(String marketHashName);

    /**
     * Milestone 7 addition — {@code PriceSyncJob} synchronizes active items only
     * (M7 Implementation Authorization §5). Inactive items are skipped, never deleted, and
     * keep their existing {@code item_prices} history untouched.
     */
    List<Item> findByIsActiveTrue();
}
