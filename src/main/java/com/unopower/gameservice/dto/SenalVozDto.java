package com.unopower.gameservice.dto;

/** Senal WebRTC entregada al objetivo por /user/queue/voice. Identidad del emisor: del Principal. */
public record SenalVozDto(String deUserId, String deUsername, String tipo, String datos) {
}
