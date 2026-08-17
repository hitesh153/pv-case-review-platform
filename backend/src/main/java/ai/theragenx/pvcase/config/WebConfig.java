package ai.theragenx.pvcase.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the reviewer UI.
 *
 * <p>The React app runs on a different port to this service in development, so
 * without this every call from the browser fails preflight — and it fails in a
 * way that looks like the backend is down rather than like a policy rejection.
 *
 * <p>Origins are listed explicitly and come from configuration rather than being
 * wildcarded. {@code allowedOrigins("*")} would work today and be exactly the
 * line someone copies into a deployment later.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(
            @Value("${pvcase.cors.allowed-origins:"
                    + "http://localhost:5173,http://127.0.0.1:5173,"
                    + "http://localhost:3000,http://127.0.0.1:3000}")
            String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
