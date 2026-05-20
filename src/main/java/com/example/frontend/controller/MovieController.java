package com.example.frontend.controller;

import com.example.frontend.dto.*;
import com.example.frontend.service.CustomerService;
import com.example.frontend.service.LocationService;
import com.example.frontend.service.MovieService;
import com.example.frontend.service.RentalService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@RequestMapping("/movies")
public class MovieController {

    private static final Logger log = LoggerFactory.getLogger(MovieController.class);

    private final MovieService movieService;
    private final CustomerService customerService;
    private final RentalService rentalService;
    private final LocationService locationService;

    public MovieController(MovieService movieService, CustomerService customerService,
                           RentalService rentalService, LocationService locationService) {
        this.movieService = movieService;
        this.customerService = customerService;
        this.rentalService = rentalService;
        this.locationService = locationService;
    }

    // ===== Movie Listing =====
    @GetMapping
    public String movies(HttpSession session, Model model,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "10") int size,
                         @RequestParam(required = false) String search,
                         @RequestParam(required = false) String searchType) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        Map<String, Object> moviesPage;

        if (search != null && !search.isBlank()) {
            if ("actor".equals(searchType)) {
                moviesPage = movieService.searchByActor(token, search, page, size);
            } else if ("category".equals(searchType)) {
                moviesPage = movieService.searchByCategory(token, search, page, size);
            } else {
                moviesPage = movieService.searchMovies(token, search, page, size);
            }
        } else {
            moviesPage = movieService.getMovies(token, page, size);
        }

        model.addAttribute("moviesPage", moviesPage);
        model.addAttribute("currentPage", page);
        model.addAttribute("search", search);
        model.addAttribute("searchType", searchType != null ? searchType : "title");
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("role", session.getAttribute("role"));
        return "movies";
    }

    // ===== Movie Details =====
    @GetMapping("/{id}")
    public String movieDetails(HttpSession session, Model model,
                               @PathVariable Integer id,
                               @RequestParam(defaultValue = "false") boolean viewOnly) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        MovieDetailsDto details = movieService.getMovieDetails(token, id);
        InventoryDto inventory = movieService.getInventory(token, id);

        model.addAttribute("movie", details);
        model.addAttribute("inventory", inventory);
        model.addAttribute("viewOnly", viewOnly);
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("role", session.getAttribute("role"));
        return "movie-details";
    }

    // ===== Rental Flow — Step 1: Customer Email Lookup =====
    @GetMapping("/{id}/rent")
    public String rentMoviePage(HttpSession session, Model model, @PathVariable Integer id) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        MovieDetailsDto movie = movieService.getMovieDetails(token, id);
        InventoryDto inventory = movieService.getInventory(token, id);

        model.addAttribute("movie", movie);
        model.addAttribute("inventory", inventory);
        model.addAttribute("countries", locationService.getCountries());
        if (!model.containsAttribute("customerRequest")) {
            model.addAttribute("customerRequest", new CustomerRequestDto());
        }
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("role", session.getAttribute("role"));
        return "movie-rent";
    }

    private static final java.util.regex.Pattern EMAIL_RE = java.util.regex.Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    @PostMapping("/{id}/rent/lookup")
    public String lookupCustomerForRental(HttpSession session,
                                          @PathVariable Integer id,
                                          @RequestParam String email,
                                          RedirectAttributes redirectAttributes) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        String trimmed = email == null ? "" : email.trim();
        if (!EMAIL_RE.matcher(trimmed).matches()) {
            redirectAttributes.addFlashAttribute("error",
                    "Please enter a valid email address (e.g. mary@example.com).");
            redirectAttributes.addFlashAttribute("prefillEmail", trimmed);
            return "redirect:/movies/" + id + "/rent";
        }

        CustomerResponseDto customer = customerService.findCustomerByEmail(token, trimmed);

        if (customer == null) {
            // Stay on the rent page, show inline add-customer form prefilled with email
            redirectAttributes.addFlashAttribute("customerNotFound", true);
            redirectAttributes.addFlashAttribute("prefillEmail", email);
            return "redirect:/movies/" + id + "/rent";
        }

        // Customer found — go to confirmation page
        return "redirect:/movies/" + id + "/rent/confirm?customerId=" + customer.getCustomerId();
    }

    // Create a customer inline during the rental flow and continue to payment.
    // Customer is ALWAYS scoped to the logged-in staff's store; we ignore any
    // storeId the form might send so a staff at store 2 can't accidentally
    // create a customer in store 1 (and lose visibility of them).
    @PostMapping("/{id}/rent/create-customer")
    public String createCustomerForRental(HttpSession session,
                                          @PathVariable Integer id,
                                          @ModelAttribute("customerRequest") CustomerRequestDto customerRequest,
                                          RedirectAttributes redirectAttributes) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        Integer staffStoreId = (Integer) session.getAttribute("storeId");
        if (staffStoreId != null) {
            customerRequest.setStoreId(staffStoreId);
        }

        try {
            String newCustomerId = customerService.createCustomer(token, customerRequest);
            return "redirect:/movies/" + id + "/rent/confirm?customerId=" + newCustomerId;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            redirectAttributes.addFlashAttribute("customerNotFound", true);
            redirectAttributes.addFlashAttribute("prefillEmail", customerRequest.getEmail());
            return "redirect:/movies/" + id + "/rent";
        }
    }

    // AJAX — cities for the inline add-customer form's cascading dropdown
    @GetMapping("/{id}/rent/cities")
    @ResponseBody
    public java.util.List<CityResponseDto> rentFlowCities(@RequestParam Integer countryId) {
        return locationService.getCities(countryId);
    }

    // ===== Rental Flow — Step 2: Payment Confirmation =====
    @GetMapping("/{id}/rent/confirm")
    public String confirmRentalPage(HttpSession session, Model model,
                                    @PathVariable Integer id,
                                    @RequestParam Integer customerId) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        MovieDetailsDto movie = movieService.getMovieDetails(token, id);
        InventoryDto inventory = movieService.getInventory(token, id);
        CustomerResponseDto customer = customerService.getCustomerById(token, customerId);

        // Auto-pick: staffId from session, inventoryId from backend (first free copy at this store)
        Integer staffId = (Integer) session.getAttribute("staffId");
        Integer autoInventoryId = movieService.getNextAvailableInventoryId(token, id);

        model.addAttribute("movie", movie);
        model.addAttribute("inventory", inventory);
        model.addAttribute("customer", customer);
        model.addAttribute("staffId", staffId);
        model.addAttribute("autoInventoryId", autoInventoryId);
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("role", session.getAttribute("role"));
        return "movie-rent-confirm";
    }

    // ===== Rental Flow — Final: Create Rental =====
    @PostMapping("/{id}/rent/create")
    public String createRentalFromMovie(HttpSession session,
                                        @PathVariable Integer id,
                                        @RequestParam Integer inventoryId,
                                        @RequestParam Integer customerId,
                                        @RequestParam Integer staffId,
                                        RedirectAttributes redirectAttributes) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        try {
            RentalRequestDto rentalRequest = new RentalRequestDto();
            rentalRequest.setInventoryId(inventoryId);
            rentalRequest.setCustomerId(customerId);
            rentalRequest.setStaffId(staffId);

            // Single REST call — backend returns all data the success page needs.
            RentalConfirmationDto confirmation = rentalService.createRental(token, rentalRequest);

            redirectAttributes.addFlashAttribute("confirmation", confirmation);
            return "redirect:/movies/" + id + "/rent/success";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/movies/" + id;
        }
    }

    @GetMapping("/{id}/rent/success")
    public String rentalSuccessPage(HttpSession session, Model model, @PathVariable Integer id) {
        String token = (String) session.getAttribute("token");
        if (token == null) return "redirect:/login";

        // If reached without flash data (e.g. page refresh), send back to the movie
        if (!model.containsAttribute("confirmation")) {
            return "redirect:/movies/" + id;
        }
        model.addAttribute("filmId", id);
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("role", session.getAttribute("role"));
        return "rental-success";
    }
}
