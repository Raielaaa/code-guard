package com.repo.guard.service.gitlab;

import com.fasterxml.jackson.databind.JsonNode;

public interface GitlabEventHandler {
    //  determines if this strategy supports the given event type
    boolean supports(String eventType);
    //  executes the specific logic for the event
    void handleEvent(JsonNode payload) throws Exception;
}
