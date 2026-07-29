package com.example;

import com.example.repository.UserRepository;

import java.util.List;

/**
 * 示例服务类，用于测试代码分析和分块
 */
public class SampleService extends BaseService implements ServiceInterface {

    private final UserRepository userRepository;

    public SampleService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User findUserById(Long id) {
        return userRepository.findById(id);
    }

    public List<User> findAllUsers() {
        return userRepository.findAll();
    }

    @Override
    public void initialize() {
        System.out.println("Service initialized");
    }
}
