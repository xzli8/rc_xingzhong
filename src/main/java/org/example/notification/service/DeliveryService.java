package org.example.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.example.notification.model.DeliveryResult;
import org.example.notification.model.NotificationRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 对外HTTP投递执行服务
 */
@Slf4j
@Service
public class DeliveryService {

    private final RestTemplate restTemplate;

    public DeliveryService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 执行投递 - 调用外部HTTP接口
     * 成功判定：HTTP 2xx
     */
    public DeliveryResult execute(String taskId, NotificationRequest request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (request.getHeaders() != null) {
                request.getHeaders().forEach(headers::set);
            }
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }

            HttpEntity<String> entity = new HttpEntity<String>(request.getBody(), headers);
            HttpMethod method = HttpMethod.resolve(request.getHttpMethod().toUpperCase());
            if (method == null) {
                method = HttpMethod.POST;
            }

            ResponseEntity<String> response = restTemplate.exchange(
                    request.getTargetUrl(), method, entity, String.class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            log.info("投递任务[{}] 结果: status={}, success={}", taskId, response.getStatusCodeValue(), success);

            return DeliveryResult.builder()
                    .success(success)
                    .httpStatusCode(response.getStatusCodeValue())
                    .build();
        } catch (Exception e) {
            log.error("投递任务[{}] 异常: {}", taskId, e.getMessage());
            return DeliveryResult.builder()
                    .success(false)
                    .httpStatusCode(0)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
