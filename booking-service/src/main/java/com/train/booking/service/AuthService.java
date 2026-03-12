package com.train.booking.service;

import com.train.booking.api.dto.AuthRequest;
import com.train.booking.api.dto.AuthResponse;
import com.train.booking.domain.User;
import com.train.booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Transactional
    public AuthResponse register(AuthRequest request) {
        if (userRepository.existsByEmail(request.getEmail().trim().toLowerCase())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User user = User.builder()
            .email(request.getEmail().trim().toLowerCase())
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .build();
        user = userRepository.save(user);
        String userId = String.valueOf(user.getId());
        auditLogService.log(userId, "REGISTER", "email=" + user.getEmail());
        return AuthResponse.builder()
            .userId(userId)
            .email(user.getEmail())
            .build();
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
            .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        String userId = String.valueOf(user.getId());
        auditLogService.log(userId, "LOGIN", "email=" + user.getEmail());
        return AuthResponse.builder()
            .userId(userId)
            .email(user.getEmail())
            .build();
    }
}
