package com.electrahub.paymentgateway.api;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookBatchReceipt;
import com.electrahub.paymentgateway.service.GatewayWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gateway/webhooks")
public class GatewayWebhookController {

    private final GatewayWebhookService webhookService;

    public GatewayWebhookController(GatewayWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(path = "/{connectionId}", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public GatewayWebhookBatchReceipt receive(
            @PathVariable UUID connectionId,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> headers.put(name.toLowerCase(), request.getHeader(name)));
        return webhookService.receive(connectionId, rawBody, headers);
    }
}
