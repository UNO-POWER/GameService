package com.unopower.gameservice.dto;

import java.time.Instant;

public record MensajeChatDto(String autorUserId, String autorUsername, String texto, Instant enviadoEn) {
}
