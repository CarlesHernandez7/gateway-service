package com.chernandez.gatewayservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "JWT_SECRET_KEY=VGhpc0lzQVRlc3RLZXlGb3JHYXRld2F5U2VydmljZUFuZEl0SXNMb25nRW5vdWdo",
    "INTERNAL_API_KEY=test-internal-key"
})
class GatewayServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
