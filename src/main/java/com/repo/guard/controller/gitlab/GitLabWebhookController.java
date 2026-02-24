package com.repo.guard.controller.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.repo.guard.service.gitlab.GitLabWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping(path = "/${gitlab.path}")
public class GitLabWebhookController {

    private final GitLabWebhookService webhookService;

    //  pull the expected secret token from application properties to verify incoming webhooks
    @Value("${gitlab.webhook.secret}")
    private String expectedSecretToken;

    /**
     * handles incoming webhook events from gitlab, verifying authenticity before processing
     *
     * @param eventType
     * @param secretToken
     * @param payload
     * @return
     */
    @PostMapping("/gitlab")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Gitlab-Event") String eventType,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String secretToken,
            @RequestBody JsonNode payload) {

        //  verify the request actually came from gitlab repo using the secret token
        if (expectedSecretToken == null || !expectedSecretToken.equals(secretToken)) {
            log.warn("Unauthorized webhook attempt blocked.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Token");
        }

        //  we only care about merge request events; ignore pushes, pipelines, etc.
        if (!"Merge Request Hook".equals(eventType)) {
            return ResponseEntity.ok("Ignored: Not a Merge Request event");
        }

        //  process the ai review in a background thread to prevent gitlab timeouts
        webhookService.processMergeRequestEventAsync(payload);

        //  return immediately so gitlab registers a successful webhook delivery
        return ResponseEntity.ok("Webhook received. AI Review started.");
    }
}