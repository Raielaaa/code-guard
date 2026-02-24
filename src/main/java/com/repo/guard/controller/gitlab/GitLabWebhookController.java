package com.repo.guard.controller.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.repo.guard.model.repo.CodeChunkRepository;
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
    private final CodeChunkRepository chunkRepository;
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

        //  accept both merge request hooks and push hooks (commits)
        if (!"Merge Request Hook".equals(eventType) && !"Push Hook".equals(eventType)) {
            return ResponseEntity.ok("Ignored: Not a Merge Request event");
        }

        //  process the AI review in a background thread to prevent gitlab timeouts
        webhookService.processGitlabEventAsync(payload);

        //  return immediately so gitlab registers a successful webhook delivery
        return ResponseEntity.ok("Webhook received. AI Review started.");
    }

    /**
     * utility endpoint to clear vectors from the database, either for a specific repository or entirely
     *
     * @param repoUrl
     * @return
     */
    @DeleteMapping("/wipe-vectors")
    public ResponseEntity<String> wipeVectors(@RequestParam(required = false) String repoUrl) {
        if (repoUrl != null) {
            //  wipe only the specific repository
            chunkRepository.deleteByRepoUrl(repoUrl);
            return ResponseEntity.ok("Cleared all vectors for repo: " + repoUrl);
        } else {
            //  wipe the entire database
//            chunkRepository.deleteAll();
//            return ResponseEntity.ok("Cleared ALL vectors in the database.");
            return ResponseEntity.ok("Wipe all vectors is currently disabled to prevent accidental data loss.");
        }
    }
}