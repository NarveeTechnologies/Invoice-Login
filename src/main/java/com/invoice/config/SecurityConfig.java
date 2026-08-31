package com.invoice.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Login-service is the only service that exposes unauthenticated endpoints (login,
 * register, email-check, OTP). Everything else — profile updates, role admin, manage
 * users — requires a JWT that the {@link JwtAuthFilter} validates and pins to the
 * adminId tenant boundary.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	@Value("${cors.allowed-origins:http://localhost:4200}")
	private String allowedOrigins;

	private final JwtAuthFilter jwtAuthFilter;

	public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
		this.jwtAuthFilter = jwtAuthFilter;
	}

	@Bean
	public WebSecurityCustomizer webSecurityCustomizer() {
		return (web) -> web.ignoring().requestMatchers("/uploads/**");
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			.cors(cors -> {})
			.sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				.requestMatchers(
						"/auth/login",
						"/auth/login/send-otp",
						"/auth/register",
						"/auth/register/send-otp",
						"/auth/login/verify-otp",
						"/auth/check-email/**",
						"/auth/check-token",
						"/auth/validate-token",
						"/auth/get-registration-token",
						"/companies",
						"/companies/active",
						"/companies/{domain}",
						"/actuator/health",
						"/actuator/info"
				).permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
			.formLogin(form -> form.disable())
			.httpBasic(b -> b.disable());
		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		List<String> origins = Arrays.stream(allowedOrigins.split(","))
				.map(String::trim).filter(s -> !s.isEmpty()).toList();
		config.setAllowedOriginPatterns(origins);
		config.setAllowCredentials(true);
		config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(Arrays.asList(
				"Authorization", "Content-Type", "Accept", "X-Requested-With", "X-Correlation-Id"));
		config.setExposedHeaders(Arrays.asList("Authorization", "Content-Disposition", "X-Correlation-Id"));
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
