package sosohappy.authservice.service;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import sosohappy.authservice.entity.*;
import sosohappy.authservice.exception.custom.BadRequestException;
import sosohappy.authservice.exception.custom.ForbiddenException;
import sosohappy.authservice.jwt.service.JwtService;
import sosohappy.authservice.kafka.KafkaProducer;
import sosohappy.authservice.oauth2.converter.CustomRequestEntityConverter;
import sosohappy.authservice.repository.UserRepository;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@EnableScheduling
@RequiredArgsConstructor
@Service
@Transactional
@Slf4j
public class UserService {

    private static Map<String, String> authorizeCodeAndChallengeMap = new HashMap<>();

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectProvider<UserService> userServiceProvider;
    private final RestTemplate restTemplate;
    private final CustomRequestEntityConverter customRequestEntityConverter;

    public void signIn(Map<String, Object> userAttributes, String refreshToken) {
        String email = String.valueOf(userAttributes.get("email"));
        String provider = String.valueOf(userAttributes.get("provider"));
        String providerId = String.valueOf(userAttributes.get("providerId"));
        String deviceToken = String.valueOf(userAttributes.get("deviceToken"));
        String appleRefreshToken = String.valueOf(userAttributes.get("appleRefreshToken"));

        produceDeviceToken(email, deviceToken);

        userRepository.findByEmailAndProvider(email, provider)
                        .ifPresentOrElse(
                                user -> {
                                    user.updateRefreshToken(refreshToken);
                                    user.updateDeviceToken(deviceToken);
                                    user.updateAppleRefreshToken(appleRefreshToken);
                                },
                                () -> userRepository.save(
                                        User.builder()
                                                .email(email)
                                                .nickname(UUID.randomUUID().toString())
                                                .provider(provider)
                                                .providerId(providerId)
                                                .refreshToken(refreshToken)
                                                .deviceToken(deviceToken)
                                                .build()
                                )
                        );
    }

    public ResignDto resign(String email) {
        return userRepository.findByEmail(email)
                .map(user -> {
                    boolean revokeResult = handleAppleUserResign(user.getAppleRefreshToken());

                    if(revokeResult){
                        userServiceProvider.getObject()
                                .produceResign(user.getEmail(), user.getNickname());
                        userRepository
                                .delete(user);
                    }

                    return ResignDto.builder()
                            .email(email)
                            .success(revokeResult)
                            .build();
                })
                .orElseGet(() ->
                        ResignDto.builder()
                                .email(email)
                                .success(false)
                                .build()
                );
    }


    public DuplicateDto checkDuplicateNickname(String nickname) {
        return userRepository.findByNickname(nickname)
                .map(user ->
                        DuplicateDto.builder()
                                .email(user.getEmail())
                                .isPresent(true)
                                .build()
                )
                .orElseGet(() ->
                        DuplicateDto.builder()
                                .email(null)
                                .isPresent(false)
                                .build()
                );
    }

    public SetProfileDto setProfile(UserRequestDto userRequestDto) {
        return userRepository.findByEmail(userRequestDto.getEmail())
                .map(user -> {
                    user.updateProfile(userRequestDto);
                    return SetProfileDto.builder()
                            .email(user.getEmail())
                            .success(true)
                            .build();
                })
                .orElseGet(() ->
                        SetProfileDto.builder()
                                .email(userRequestDto.getEmail())
                                .success(false)
                                .build()
                );
    }

    public UserResponseDto findProfileImg(String nickname) {
        return userRepository.findByNickname(nickname)
                .map(user ->
                        UserResponseDto.builder()
                                .nickname(nickname)
                                .profileImg(user.getProfileImg())
                                .build()
                )
                .orElseGet(() -> UserResponseDto.builder().build());
    }

    public Map<String, String> getAuthorizeCode(String codeChallenge){
        if(codeChallenge == null){
            throw new BadRequestException();
        }

        String authorizeCode = UUID.randomUUID().toString();
        authorizeCodeAndChallengeMap.put(authorizeCode, codeChallenge);

        return Map.of("authorizeCode", authorizeCode);
    }

    public Map<String, String> findIntroduction(String nickname) {
        return Map.of(
                "introduction",
                userRepository.findByNickname(nickname)
                        .map(user -> user.getIntroduction() != null ? user.getIntroduction() : "")
                        .orElse("")
        );
    }

    @SneakyThrows
    public void signInWithPKCE(SignInDto signInDto, HttpServletResponse response) {

        String provider = signInDto.getProvider();
        String email = signInDto.getEmail() + "+" + provider;
        String providerId = signInDto.getProviderId();
        String authorizeCode = signInDto.getAuthorizeCode();
        String codeVerifier = signInDto.getCodeVerifier();

        if(!provider.equals("apple") && !provider.equals("google") && !provider.equals("kakao")){
            throw new ForbiddenException();
        }

        String encodedCodeChallenge = encode(codeVerifier);

        if(isValidUser(authorizeCode, encodedCodeChallenge)){

            authorizeCodeAndChallengeMap.remove(authorizeCode);

            Map<String, Object> userAttributes = Map.of(
                    "email", email,
                    "provider", provider,
                    "providerId", providerId,
                    "deviceToken", signInDto.getDeviceToken(),
                    "appleRefreshToken", provider.equals("apple") ? getAppleRefreshToken(email, signInDto.getAuthorizationCode()) : ""
            );

            String accessToken = jwtService.createAccessToken(email);
            String refreshToken = jwtService.createRefreshToken(email);

            jwtService.setAccessTokenOnHeader(response, accessToken);
            jwtService.setRefreshTokenOnHeader(response, refreshToken);

            User user = userRepository.findByEmailAndProvider(email, provider).orElse(null);

            response.setCharacterEncoding("UTF-8");
            response.setHeader("nickname", user != null && user.getNickname() != null ? Objects.requireNonNull(user).getNickname() : null);
            response.setHeader("email", email);

            signIn(userAttributes, refreshToken);
        }
        else {
            throw new ForbiddenException();
        }

    }


    // --------------------------------------------------------------- //

    @KafkaProducer(topic = "resign")
    private List<String> produceResign(String email, String nickname){
        return List.of(email, nickname);
    }

    @KafkaProducer(topic = "deviceToken")
    private List<String> produceDeviceToken(String email, String deviceToken){
        return List.of(email, deviceToken);
    }

    private String encode(String codeVerifier) throws NoSuchAlgorithmException {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-512");

        messageDigest.reset();
        messageDigest.update(codeVerifier.getBytes());
        return String.format("%064x", new BigInteger(1, messageDigest.digest()));
    }

    private boolean isValidUser(String authorizeCode, String encodedCodeChallenge) {
        return authorizeCodeAndChallengeMap.containsKey(authorizeCode) && authorizeCodeAndChallengeMap.get(authorizeCode).equals(encodedCodeChallenge);
    }

    private String getAppleRefreshToken(String email, String authorizationCode){
        String clientSecret = customRequestEntityConverter.createClientSecret();
        String tokenURI = "https://appleid.apple.com/auth/token";
        String grantType = "authorization_code";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>(){{
            add("code", authorizationCode);
            add("client_id", customRequestEntityConverter.getClientId());
            add("client_secret", clientSecret);
            add("grant_type", grantType);
        }};

        log.info("checkPoint2");

        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(params, headers);

        log.info("checkPoint3");

        try {
            ResponseEntity<TokenResponseDto> response = restTemplate.postForEntity(tokenURI, httpEntity, TokenResponseDto.class);

            log.info("checkPoint4");
            System.out.println("response : " + response);

            if(response.getStatusCode().is2xxSuccessful()){
                return Objects.requireNonNull(response.getBody()).getRefresh_token();
            } else {
                throw new ForbiddenException();
            }

        } catch (HttpClientErrorException e) {
            log.info(e.getMessage());
            log.info(e.getResponseBodyAsString());
            throw new ForbiddenException();
        }

    }

    private boolean handleAppleUserResign(String appleRefreshToken) {
        String clientSecret = customRequestEntityConverter.createClientSecret();
        String revokeURI = "https://appleid.apple.com/auth/revoke";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>(){{
            add("client_id", customRequestEntityConverter.getClientId());
            add("client_secret", clientSecret);
            add("token", appleRefreshToken);
            add("token_type_hint", "refresh_token");
        }};

        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(revokeURI, httpEntity, String.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (HttpClientErrorException e) {
            System.out.println("e.getMessage() = " + e.getMessage());
            System.out.println("e.getResponseBodyAsString() = " + e.getResponseBodyAsString());
            throw new ForbiddenException();
        }
    }

    @Scheduled(fixedRate = 600000)
    public void deleteAuthorizeCodeAndChallengeMap(){
        authorizeCodeAndChallengeMap.clear();
    }

}
