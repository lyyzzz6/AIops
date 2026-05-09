package com.netdata.ops.controller;

import com.netdata.ops.dto.response.R;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/test")
public class TestController {

    @GetMapping("/encode/{password}")
    public R<String> encode(@PathVariable String password) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String encoded = encoder.encode(password);
        return R.ok(encoded);
    }

    @GetMapping("/verify/{raw}/{encoded}")
    public R<Boolean> verify(@PathVariable String raw, @PathVariable String encoded) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        return R.ok(encoder.matches(raw, encoded));
    }
}
