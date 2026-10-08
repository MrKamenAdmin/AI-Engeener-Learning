package course.m07security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Второй слой против эксфильтрации через картинки: браузер сам не пойдёт на чужой домен,
 * даже если фильтр вывода что-то пропустил. В Spring Boot регистрируется как @Bean
 * (или то же самое через Spring Security: headers().contentSecurityPolicy(...)).
 */
public class CspFilter extends HttpFilter {
    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        res.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' https://docs.payflow.example; connect-src 'self'; frame-ancestors 'none'");
        chain.doFilter(req, res);
    }
}
