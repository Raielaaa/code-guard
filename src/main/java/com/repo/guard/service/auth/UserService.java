package com.repo.guard.service.auth;

import com.repo.guard.model.auth.UserModel;

import java.util.List;

public interface UserService {
    UserModel findById(Long id);
    List<UserModel> findByRole(String role);
    List<UserModel> getAllUsers();
    UserModel saveUser(UserModel userModel);
}
