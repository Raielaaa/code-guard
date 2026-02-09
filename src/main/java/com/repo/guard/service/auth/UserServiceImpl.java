package com.repo.guard.service.auth;

import com.repo.guard.exception.UserNotFoundException;
import com.repo.guard.model.auth.UserModel;
import com.repo.guard.model.auth.UserModelRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class UserServiceImpl implements UserService, UserDetailsService {
    private final UserModelRepository userModelRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userModelRepository.findByUsername(username)
                .map(userObj -> User.builder()
                        .username(userObj.getUsername())
                        .password(userObj.getPassword())
                        .roles(getRoles(userObj.getRole()))
                        .build()
                )
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private String[] getRoles(String role) {
        if (role == null) return new String[]{"USER"};
        return role.split(",");
    }

    @Override
    public UserModel findById(Long id) {
        return userModelRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
    }

    @Override
    public List<UserModel> findByRole(String role) {
        return userModelRepository.findByRole(role)
                .orElseThrow(() -> new UserNotFoundException("User not found with role: " + role));
    }

    @Override
    public List<UserModel> getAllUsers() {
        return userModelRepository.findAll();
    }

    @Override
    public UserModel saveUser(UserModel userModel) {
        return userModelRepository.save(userModel);
    }
}
