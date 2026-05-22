package com.invoice.config;

import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
	
	
	@Bean
	public WebSecurityCustomizer webSecurityCustomizer() {
	    return (web) -> web.ignoring().requestMatchers("/uploads/**");
	}
	
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                            "/auth/**",
                            "/uploads/**", 
                            "/auth/{filename:.+}/**",
                            "/auth/login/send-otp/**",
                            "/auth/uploads/profile",
                            "/auth/updated/save",
                            "/auth/uploads/**",
                            "/auth/login/**",
                            "/auth/manageusers/{id}",
                            "/auth/register/**",
                            "/auth/updated/email/{email}",
                            "/auth/updated/email/**",
                	        "/auth/updated/email/{email}/**",
                	        "/auth/updated/save", 
                	        "/auth/manageusers/searchAndsorting/**",
                	        "/auth/roles/search/**",
                            "/auth/otp/**",
                            "/auth/check-email/{email}",
                            "/manageusers/searchAndsorting/getall/**",
                            "/auth/updated/save/**",
                            "/companies/**"
                    ).permitAll()
                    .anyRequest().authenticated()
            )
            .formLogin(form -> form.disable())
            .httpBasic(httpBasic -> httpBasic.disable());

        return http.build();
    }
}