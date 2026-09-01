package com.recoverai.backend.repository;

import com.recoverai.backend.entity.Subscription;
import com.recoverai.backend.entity.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {
    List<Subscription> findByStatus(SubscriptionStatus status);
}
