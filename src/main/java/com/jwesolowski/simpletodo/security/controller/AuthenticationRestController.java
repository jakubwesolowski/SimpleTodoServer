package com.jwesolowski.simpletodo.security.controller;

import com.jwesolowski.simpletodo.domain.Project;
import com.jwesolowski.simpletodo.domain.User;
import com.jwesolowski.simpletodo.repository.ProjectRepository;
import com.jwesolowski.simpletodo.repository.UserRepository;
import com.jwesolowski.simpletodo.security.JwtAuthenticationRequest;
import com.jwesolowski.simpletodo.security.JwtRegisterRequest;
import com.jwesolowski.simpletodo.security.JwtTokenUtil;
import com.jwesolowski.simpletodo.security.JwtUser;
import com.jwesolowski.simpletodo.security.service.JwtAuthenticationResponse;
import com.jwesolowski.simpletodo.service.EmailService;
import java.util.Date;
import java.util.Objects;
import javax.mail.internet.AddressException;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "http://localhost:4200", maxAge = 3600)
public class AuthenticationRestController {

  @Value("${jwt.header}")
  private String tokenHeader;

  @Autowired
  private PasswordEncoder encoder;

  @Autowired
  private EmailService emailService;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private ProjectRepository projectRepository;

  @Autowired
  private AuthenticationManager authenticationManager;

  @Autowired
  private JwtTokenUtil jwtTokenUtil;

  @Autowired
  @Qualifier("jwtUserDetailsService")
  private UserDetailsService userDetailsService;

  @RequestMapping(value = "${jwt.route.authentication.path}", method = RequestMethod.POST)
  public ResponseEntity<?> createAuthenticationToken(
      @RequestBody JwtAuthenticationRequest authenticationRequest) throws AuthenticationException {

    try {
      authenticate(authenticationRequest.getUsername(), authenticationRequest.getPassword());
    } catch (BadCredentialsException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body("Bad credentials!");
    } catch (DisabledException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body("User is disabled!");
    }

    // Reload password post-security so we can generate the token
    final UserDetails userDetails = userDetailsService
        .loadUserByUsername(authenticationRequest.getUsername());

    final String token = jwtTokenUtil.generateToken(userDetails);

    // Return the token
    return ResponseEntity
        .ok(new JwtAuthenticationResponse(authenticationRequest.getUsername(), token));
  }

  @RequestMapping(value = "${jwt.route.authentication.register}", method = RequestMethod.POST)
  public ResponseEntity<String> register(
      @RequestBody JwtRegisterRequest registerRequest) throws AuthenticationException {

    if (userRepository.existsByUsername(registerRequest.getUsername())) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body("User with this name already exists!");
    }

    if (userRepository.existsByUsername(registerRequest.getUsername()) || userRepository.findAll()
        .stream().anyMatch(user -> user.getEmail().equals(registerRequest.getEmail()))) {

      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body("User with this email already exists!");
    }

    User user = new User();
    user.setUsername(registerRequest.getUsername());
    user.setPassword(encoder.encode(registerRequest.getPassword()));
    user.setEnabled(true);
    user.setLastPasswordResetDate(new Date());
    user.setFirstname(registerRequest.getFirstName());
    user.setLastname(registerRequest.getLastName());
    user.setEmail(registerRequest.getEmail());

    userRepository.save(user);

    Project newInbox = new Project();
    newInbox.setName("Inbox");
    Project proj = projectRepository.saveAndFlush(newInbox);
    proj.setUser(user);
    projectRepository.saveAndFlush(proj);

    try {
      emailService.sendRegisterEmail(user);
    } catch (AddressException e) {
      e.printStackTrace();
    }

    return ResponseEntity.status(HttpStatus.OK).body("User created!");
  }

  @RequestMapping(value = "${jwt.route.authentication.refresh}", method = RequestMethod.GET)
  public ResponseEntity<?> refreshAndGetAuthenticationToken(HttpServletRequest request) {
    String authToken = request.getHeader(tokenHeader);
    final String token = authToken.substring(7);
    String username = jwtTokenUtil.getUsernameFromToken(token);
    JwtUser user = (JwtUser) userDetailsService.loadUserByUsername(username);

    if (jwtTokenUtil.canTokenBeRefreshed(token, user.getLastPasswordResetDate())) {
      String refreshedToken = jwtTokenUtil.refreshToken(token);
      return ResponseEntity.ok(new JwtAuthenticationResponse(user.getUsername(), refreshedToken));
    } else {
      return ResponseEntity.badRequest().body(null);
    }
  }

  @ExceptionHandler({AuthenticationException.class})
  public ResponseEntity<String> handleAuthenticationException(AuthenticationException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
  }

  /**
   * Authenticates the user. If something is wrong, an {@link AuthenticationException} will be
   * thrown
   */
  private void authenticate(String username, String password) {
    Objects.requireNonNull(username);
    Objects.requireNonNull(password);

    authenticationManager
        .authenticate(new UsernamePasswordAuthenticationToken(username, password));
  }
}
