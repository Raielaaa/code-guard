package com.repo.guard.controller.auth;

import com.repo.guard.dto.ApiResponse;
import com.repo.guard.dto.LoginForm;
import com.repo.guard.dto.RequestDto;
import com.repo.guard.dto.ResponseDto;
import com.repo.guard.jwt.JwtService;
import com.repo.guard.model.auth.UserModel;
import com.repo.guard.service.auth.UserServiceImpl;
import lombok.AllArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping(path = "/${auth.path}")
public class AuthController {
    private UserServiceImpl userService;
    private PasswordEncoder passwordEncoder;
    private AuthenticationManager authenticationManager;
    private JwtService jwtService;
    private ModelMapper modelMapper;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse> getSystemStatus() {
        return ResponseEntity.ok().body(new ApiResponse(
                "Ok",
                "Systems up"
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse> registerUser(@RequestBody RequestDto requestDto) {
        return ResponseEntity.ok().body(new ApiResponse(
                "User created",
                modelMapper.map(userService.saveUser(
                                UserModel.builder()
                                        .username(requestDto.getUsername())
                                        .password(passwordEncoder.encode(requestDto.getPassword()))
                                        .role(requestDto.getRole())
                                        .build()
                        ),
                        ResponseDto.class)
        ));
    }

    @PostMapping("/login")
    public String authenticateAndGetToken(@RequestBody LoginForm loginForm) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginForm.getUsername(),
                        loginForm.getPassword()
                )
        );

        if (authentication.isAuthenticated()) {
            return jwtService.generateToken(userService.loadUserByUsername(loginForm.getUsername()));
        } else {
            throw new UsernameNotFoundException("Invalid user request.");
        }
    }
}
