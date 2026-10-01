package com.aircraft.mro.smoke;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.repository.AircraftRepository;

/**
 * 仅 smoke profile：为模块八端到端冒烟补齐 21 架飞机台账（与生产 schema.sql 一致）。
 */
@Component
@Profile("smoke")
public class SmokeAircraftSeeder {

    private final AircraftRepository aircraftRepository;

    public SmokeAircraftSeeder(AircraftRepository aircraftRepository) {
        this.aircraftRepository = aircraftRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(5)
    @Transactional
    public void seed() {
        Object[][] rows = {
                {"B-6032", "B737", "华夏长风航空"}, {"B-6108", "A320", "华夏长风航空"},
                {"B-6571", "A330", "东方云途航空"}, {"B-6688", "B737", "东方云途航空"},
                {"B-2819", "ARJ21", "北疆支线航空"}, {"B-3027", "ARJ21", "北疆支线航空"},
                {"B-7325", "A320", "南岭快线航空"}, {"B-7411", "A330", "南岭快线航空"},
                {"B-5206", "B737", "华夏长风航空"}, {"B-5590", "A320", "东方云途航空"},
                {"B-3305", "ARJ21", "北疆支线航空"}, {"B-8062", "B737", "南岭快线航空"},
                {"B-9001", "ARJ21", "北疆支线航空"}, {"B-9002", "B737", "华夏长风航空"},
                {"B-9003", "B737", "东方云途航空"}, {"B-9004", "ARJ21", "南岭快线航空"},
                {"B-9005", "A320", "华夏长风航空"}, {"B-9006", "ARJ21", "北疆支线航空"},
                {"B-11001", "ARJ21", "北疆支线航空"}, {"B-11030", "ARJ21", "北疆支线航空"},
                {"B-11099", "ARJ21", "北疆支线航空"}
        };
        long id = 1;
        for (Object[] r : rows) {
            Aircraft a = new Aircraft();
            a.setTailNo((String) r[0]);
            a.setModel((String) r[1]);
            a.setAirline((String) r[2]);
            a.setTotalFh(new BigDecimal("10000.00"));
            a.setTotalCycles(8000);
            a.setAirworthy("AIRWORTHY");
            a.setUpdatedAt(LocalDateTime.now());
            aircraftRepository.save(a);
            id++;
        }
    }
}
