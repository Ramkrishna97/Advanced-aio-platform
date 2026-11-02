package com.example.dashboard.auth;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ActivityRecorder {
    private final UserActivityRepository activities;
    ActivityRecorder(UserActivityRepository activities) { this.activities = activities; }

    @Async
    @Transactional
    public void record(Long userId, String action) {
        activities.save(new UserActivity(userId, action));
    }
}
