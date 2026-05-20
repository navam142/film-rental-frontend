package com.example.frontend.controller;

import com.example.frontend.dto.LoginRequestDto;
import com.example.frontend.dto.LoginResponseDto;
import com.example.frontend.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String loginPage(Model model, HttpSession session) {
        if (session.getAttribute("token") != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("loginRequest", new LoginRequestDto());
        return "login";
    }

    @PostMapping("/login")
    public String login(@ModelAttribute LoginRequestDto loginRequest, HttpSession session, RedirectAttributes redirectAttributes) {
        try {
            LoginResponseDto response = authService.login(loginRequest);
            session.setAttribute("token", response.getToken());
            session.setAttribute("username", response.getUsername());
            session.setAttribute("role", response.getRole());
            if (response.getStaffId() != null) {
                session.setAttribute("staffId", response.getStaffId());
            }
            if (response.getStoreId() != null) {
                session.setAttribute("storeId", response.getStoreId());
            }
            log.info("User {} logged in", response.getUsername());
            return "redirect:/dashboard";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/login";
        }
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
