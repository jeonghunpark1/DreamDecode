// src/main/java/est/DreamDecode/service/AuthService.java
package est.DreamDecode.service;

import est.DreamDecode.domain.RefreshToken;
import est.DreamDecode.domain.User;
import est.DreamDecode.repository.RefreshTokenRepository;
import est.DreamDecode.repository.UserRepository;
import est.DreamDecode.config.JwtTokenProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwt;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final int RT_DAYS = 14;

    @Transactional
    public Tokens login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String at = jwt.createAccessToken(user.getId(), user.getEmail());
        String rt = jwt.createRefreshToken(user.getId());

        refreshTokenRepository.findByUser(user).ifPresent(refreshTokenRepository::delete);

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .refreshToken(rt)
                .expiresAt(LocalDateTime.now().plusDays(RT_DAYS))
                .build();
        refreshTokenRepository.save(token);

        return new Tokens(at, rt);
    }

    @Transactional
    public Tokens refresh(String presentedRt) {
        // 파싱 실패(만료/위조) 시 예외 던지면 컨트롤러에서 401로 변환
        Long userId = jwt.getUserId(presentedRt);
        User user = userRepository.findById(userId).orElseThrow();

        RefreshToken stored = refreshTokenRepository.findByUser(user)
                .orElseThrow(() -> new IllegalArgumentException("리프레시 토큰이 없습니다. 다시 로그인하세요."));

        if (!stored.getRefreshToken().equals(presentedRt)) {
            refreshTokenRepository.deleteByUser(user); // 탈취/불일치 대응
            throw new IllegalStateException("리프레시 토큰 불일치. 다시 로그인하세요.");
        }

        String newAt = jwt.createAccessToken(user.getId(), user.getEmail());
        String newRt = jwt.createRefreshToken(user.getId());

        stored.setRefreshToken(newRt);
        stored.setExpiresAt(LocalDateTime.now().plusDays(RT_DAYS));
        refreshTokenRepository.save(stored);

        return new Tokens(newAt, newRt);
    }

    @Transactional
    public void logout(Long userId) {
        userRepository.findById(userId).ifPresent(u -> refreshTokenRepository.deleteByUser(u));
    }

    /** 쿠키에서 받은 RT만으로도 로그아웃 가능하게 */
    @Transactional
    public void logoutByRefreshToken(String presentedRt) {
        Long userId = jwt.getUserId(presentedRt);
        userRepository.findById(userId).ifPresent(u -> refreshTokenRepository.deleteByUser(u));
    }

    public record Tokens(String accessToken, String refreshToken) {}
}
