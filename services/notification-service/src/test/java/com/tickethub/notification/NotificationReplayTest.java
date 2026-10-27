package com.tickethub.notification;

import com.tickethub.notification.domain.Notification;
import com.tickethub.notification.repo.NotificationRepository;
import com.tickethub.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

/** Scenario 12: a replay instance rebuilds its records from old events and must never email customers again. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notificationreplaytest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false",
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "tickethub.kafka.topic.auto-create=false",
        "management.health.mail.enabled=false",
        "notification.replay-mode=true"
})
class NotificationReplayTest {

    @Autowired NotificationService service;
    @Autowired NotificationRepository repo;
    @MockBean JavaMailSender mailSender;

    @Test
    void replayRebuildsRecordsWithoutSendingEmail() {
        service.handle(NotificationFlowTest.event("BookingConfirmed", 1, "r-1", "rb-1", "a@b.com"));
        service.handle(NotificationFlowTest.event("BookingConfirmed", 1, "r-1", "rb-1", "a@b.com")); // duplicate in the old topic
        assertEquals(Notification.Status.REPLAYED, repo.findByEventId("r-1").orElseThrow().getStatus());
        verifyNoInteractions(mailSender);
    }
}
