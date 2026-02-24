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

    /**
     * checks the health and status of the authentication system
     *
     * @return
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse> getSystemStatus() {
        //  return a simple ok response indicating the system is up
        return ResponseEntity.ok().body(new ApiResponse(
                "Ok",
                "Systems up"
        ));
    }

    /**
     * registers a new user into the system with encoded credentials
     *
     * @param requestDto
     * @return
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse> registerUser(@RequestBody RequestDto requestDto) {
        //  build and save the user model, ensuring the password is safely encoded
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

    /**
     * authenticates user credentials and returns a generated jwt token if successful
     *
     * @param loginForm
     * @return
     */
    @PostMapping("/login")
    public String authenticateAndGetToken(@RequestBody LoginForm loginForm) {
        //  attempt to authenticate the provided username and password combination
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginForm.getUsername(),
                        loginForm.getPassword()
                )
        );

        //  if authentication is successful, generate and return the jwt token
        if (authentication.isAuthenticated()) {
            return jwtService.generateToken(userService.loadUserByUsername(loginForm.getUsername()));
        } else {
            //  throw an exception if the credentials do not match
            throw new UsernameNotFoundException("Invalid user request.");
        }
    }
}