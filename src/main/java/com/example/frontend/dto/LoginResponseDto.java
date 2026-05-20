package com.example.frontend.dto;

import lombok.Data;

@Data
public class LoginResponseDto {
    private String token;
    private String username;
    private String role;
    private Integer staffId;
    private Integer storeId;
}
