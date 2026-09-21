package com.xiaohua.star;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载冒烟测试。
 *
 * <p>它需要 MySQL（star_assistant 库）、Redis、以及能创建 JavaMailSender 的 {@code spring.mail.*}
 * 配置全部就绪才能通过，所以本地平时不跑它 —— 跑单元测试请挑不带 {@code @SpringBootTest} 的类。</p>
 */
@SpringBootTest
class MainApplicationTests {

    @Test
    void contextLoads() {
    }

}
