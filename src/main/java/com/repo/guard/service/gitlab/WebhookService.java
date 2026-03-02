package com.repo.guard.service.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {
    //  inject all implementations of the strategy interface automatically
    private final List<GitlabEventHandler> eventHandlers;

    /**
     * routes the incoming gitlab event to the appropriate review logic based on its kind
     *
     * @param payload
     */
    @Async
    //  runs in the background to check out the diff, query context,
    //  and post the review without blocking gitlab's webhook timeout
    public void processGitlabEventAsync(JsonNode payload) {
        try {
            String objectKind = payload.path("object_kind").asText();

            //  execute the strategy pattern to find the correct handler
            for (GitlabEventHandler handler : eventHandlers) {
                if (handler.supports(objectKind)) {
                    handler.handleEvent(payload);
                    return;
                }
            }

            log.warn("No handler found for GitLab event type: {}", objectKind);
        } catch (Exception e) {
            log.error("Failed to process GitLab Webhook", e);
        }
    }
}
