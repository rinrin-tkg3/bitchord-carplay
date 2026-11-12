package com.music.bitchord.carplay
/**
 * Self-contained HTML/CSS/JS application for the iPhone Safari / Apple CarPlay Web Companion.
 *
 * Faithfully reproduces BitChord's Android Compose UI (Liquid Glass top bar, Home shelves,
 * Quick Access 3x3 paged grid, floating MiniPlayer, GlassNavBar with standalone Search circle,
 * and Apple Music-style Now Playing sheet with collapsible header, synced lyrics, and queue)
 * using inline vector SVGs matching `BitChordIcons.kt`, `SfIcons.kt`, and `ic_logo.xml`.
 */
internal object CarPlayWebHtml {
    val HTML: String by lazy { PART_1 + PART_2 }
    private const val PART_1 = """<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<title>BitChord rinrin.ver</title>
<style>
:root {
  --bg: #09090b;
  --bg-grad-top: rgba(24, 24, 30, 0.55);
  --surface: #141418;
  --surface-elev: #1c1c22;
  --glass-bg: rgba(24, 24, 30, 0.72);
  --glass-pill-bg: rgba(255, 255, 255, 0.14);
  --glass-border: rgba(255, 255, 255, 0.16);
  --glass-highlight: rgba(255, 255, 255, 0.24);
  --glass-shadow: 0 10px 30px rgba(0, 0, 0, 0.48), inset 0 1px 0.5px rgba(255, 255, 255, 0.22);
  --solid-bg: rgba(22, 22, 27, 0.94);
  --solid-border: rgba(255, 255, 255, 0.08);
  --solid-shadow: 0 6px 20px rgba(0, 0, 0, 0.42);
  --text: #f5f5f7;
  --text-sub: rgba(235, 235, 245, 0.60);
  --text-muted: rgba(235, 235, 245, 0.38);
  --accent: #fa2d48;
  --green: #30d158;
  --blue: #5eb0ef;
  --divider: rgba(255, 255, 255, 0.08);
  --glass-blur: blur(26px) saturate(190%);
}
body.light {
  --bg: #f6f6f9;
  --bg-grad-top: rgba(230, 232, 240, 0.6);
  --surface: #ffffff;
  --surface-elev: #ebedf2;
  --glass-bg: rgba(255, 255, 255, 0.76);
  --glass-pill-bg: rgba(0, 0, 0, 0.08);
  --glass-border: rgba(0, 0, 0, 0.10);
  --glass-highlight: rgba(255, 255, 255, 0.85);
  --glass-shadow: 0 8px 26px rgba(0, 0, 0, 0.10), inset 0 1px 0.5px rgba(255, 255, 255, 0.9);
  --solid-bg: rgba(245, 245, 250, 0.95);
  --solid-border: rgba(0, 0, 0, 0.08);
  --solid-shadow: 0 6px 18px rgba(0, 0, 0, 0.08);
  --text: #111114;
  --text-sub: rgba(28, 28, 34, 0.60);
  --text-muted: rgba(28, 28, 34, 0.36);
  --divider: rgba(0, 0, 0, 0.07);
}
* {
  box-sizing: border-box;
  -webkit-tap-highlight-color: transparent;
}
html, body {
  margin: 0;
  padding: 0;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", "Hiragino Sans", sans-serif;
  overflow-x: hidden;
  user-select: none;
  -webkit-user-select: none;
  -webkit-font-smoothing: antialiased;
}
svg {
  display: block;
  flex-shrink: 0;
}
/* Liquid Glass vs Standard Frosted Surface */
.glass-surface {
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  transition: background 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease;
}
body.no-liquid .glass-surface {
  background: var(--solid-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--solid-border);
  box-shadow: var(--solid-shadow);
}
/* Floating Top Bar (FrostedTopBar.kt) */
.top-scrim {
  position: fixed;
  top: 0; left: 0; right: 0;
  height: calc(env(safe-area-inset-top, 0px) + 74px);
  background: linear-gradient(180deg, var(--bg) 0%, rgba(9,9,11,0.55) 55%, rgba(9,9,11,0) 100%);
  pointer-events: none;
  z-index: 48;
}
body.light .top-scrim {
  background: linear-gradient(180deg, var(--bg) 0%, rgba(246,246,249,0.6) 55%, rgba(246,246,249,0) 100%);
}
.top-bar {
  position: fixed;
  top: 0; left: 0; right: 0;
  z-index: 50;
  padding: calc(env(safe-area-inset-top, 10px) + 6px) 16px 8px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  pointer-events: none;
}
.top-bar-left {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.top-back-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: none;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text);
}
.top-back-btn.visible {
  display: flex;
}
.top-back-btn:active {
  transform: scale(0.93);
}
.top-bar-right {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.fps-pill {
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.3px;
  font-variant-numeric: tabular-nums;
  color: var(--text);
  white-space: nowrap;
}
.carplay-circle-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text);
  transition: transform 0.14s ease, color 0.18s ease;
}
.carplay-circle-btn:active {
  transform: scale(0.93);
}
.carplay-circle-btn.active {
  color: var(--green);
}
.carplay-circle-btn.connected:not(.active) {
  color: var(--blue);
}
.carplay-dot {
  position: absolute;
  top: 7px;
  right: 7px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: transparent;
  transition: background 0.2s ease, box-shadow 0.2s ease;
}
.carplay-circle-btn.active .carplay-dot {
  background: var(--green);
  box-shadow: 0 0 6px var(--green);
}
.carplay-circle-btn.connected:not(.active) .carplay-dot {
  background: var(--blue);
}
/* 3-Icon Theme Selector Pill (TopBarThemeSelector) */
.theme-pill {
  height: 44px;
  padding: 4px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  position: relative;
}
.theme-seg {
  width: 34px;
  height: 36px;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-sub);
  position: relative;
  z-index: 2;
  transition: color 0.18s ease, background 0.18s ease;
}
.theme-seg.active {
  color: var(--text);
  background: var(--glass-pill-bg);
}
.account-circle {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  color: var(--text);
}
.account-circle img {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}
/* Audio Unlock / CarPlay Output Banner */
.audio-banner {
  margin: calc(env(safe-area-inset-top, 10px) + 60px) 16px 6px 16px;
  padding: 11px 14px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  cursor: pointer;
  transition: transform 0.15s ease;
}
.audio-banner:active {
  transform: scale(0.985);
}
.audio-banner.active {
  border-color: rgba(48, 209, 88, 0.42);
}
.audio-banner-left {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}
.audio-banner-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text);
  flex-shrink: 0;
}
body.light .audio-banner-icon {
  background: rgba(0, 0, 0, 0.06);
}
.audio-banner.active .audio-banner-icon {
  background: rgba(48, 209, 88, 0.18);
  color: var(--green);
}
.audio-banner-text {
  min-width: 0;
}
.audio-banner-title {
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.audio-banner-sub {
  font-size: 11px;
  color: var(--text-sub);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.audio-banner-action {
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  background: var(--accent);
  color: #fff;
  white-space: nowrap;
  flex-shrink: 0;
}
.audio-banner.active .audio-banner-action {
  background: rgba(48, 209, 88, 0.18);
  color: var(--green);
}
/* Main Content Area */
.main-content {
  padding: 6px 0 calc(env(safe-area-inset-bottom, 16px) + 152px) 0;
}
/* Home Brand Header (HomeTitle in HomeScreen.kt) */
.home-brand-row {
  padding: 6px 20px 14px 20px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.home-brand-logo {
  width: 34px;
  height: 23px;
  color: var(--text);
}
.home-brand-text {
  font-size: 27px;
  font-weight: 800;
  letter-spacing: -0.6px;
  line-height: 1;
}
.home-brand-ver {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-sub);
  margin-left: 2px;
  align-self: flex-end;
  padding-bottom: 2px;
}
/* Section Headers */
.section-header {
  padding: 16px 20px 10px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.section-title {
  font-size: 21px;
  font-weight: 800;
  letter-spacing: -0.4px;
}
.section-sub {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-sub);
  margin-bottom: 2px;
}
/* Recommended 4-Row Horizontal Snap Pager (RecommendedSection in HomeScreen.kt) */
.rec-pager {
  display: flex;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  gap: 14px;
  padding: 0 20px 6px 20px;
  scrollbar-width: none;
}
.rec-pager::-webkit-scrollbar {
  display: none;
}
.rec-column {
  flex: 0 0 calc(100vw - 48px);
  max-width: 356px;
  scroll-snap-align: start;
  display: flex;
  flex-direction: column;
}
.rec-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 4px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.14s ease;
}
.rec-row:active {
  background: var(--glass-pill-bg);
}
.rec-art-wrap {
  position: relative;
  width: 48px;
  height: 48px;
  border-radius: 7px;
  overflow: hidden;
  background: var(--surface-elev);
  flex-shrink: 0;
}
.rec-art-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.rec-art-playing {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.48);
  display: none;
  align-items: center;
  justify-content: center;
}
.rec-row.playing .rec-art-playing {
  display: flex;
}
.rec-meta {
  flex: 1;
  min-width: 0;
  padding: 2px 0;
}
.rec-title {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rec-row.playing .rec-title {
  color: var(--accent);
}
.rec-sub {
  font-size: 13px;
  color: var(--text-sub);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rec-more {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  flex-shrink: 0;
}
/* Quick Access 3x3 Paged Grid (QuickAccessSection in HomeScreen.kt) */
.qa-header {
  padding: 18px 20px 10px 20px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.qa-avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  object-fit: cover;
  background: var(--surface-elev);
}
.qa-header-text {
  display: flex;
  flex-direction: column;
}
.qa-listener {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-sub);
  letter-spacing: 0.4px;
}
.qa-pager {
  display: flex;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  gap: 16px;
  padding: 0 20px;
  scrollbar-width: none;
}
.qa-pager::-webkit-scrollbar {
  display: none;
}
.qa-page {
  flex: 0 0 calc(100vw - 40px);
  max-width: 440px;
  scroll-snap-align: start;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.qa-tile {
  position: relative;
  aspect-ratio: 1 / 1;
  border-radius: 10px;
  overflow: hidden;
  background: var(--surface-elev);
  cursor: pointer;
}
.qa-tile:active {
  transform: scale(0.96);
}
.qa-tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.qa-tile-scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0,0,0,0) 38%, rgba(0,0,0,0.52) 68%, rgba(0,0,0,0.86) 100%);
}
.qa-tile-label-row {
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: 7px;
  display: flex;
  align-items: center;
  gap: 2px;
}
.qa-tile-title {
  font-size: 11.5px;
  font-weight: 700;
  line-height: 1.2;
  color: #ffffff;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.qa-dots {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 12px 0 4px 0;
}
.qa-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-muted);
  transition: background 0.18s ease, transform 0.18s ease;
}
.qa-dot.active {
  background: var(--text);
  transform: scale(1.12);
}
/* Horizontal Shelves (Shelf.kt) */
.shelf-scroll {
  display: flex;
  overflow-x: auto;
  gap: 14px;
  padding: 0 20px 8px 20px;
  scrollbar-width: none;
}
.shelf-scroll::-webkit-scrollbar {
  display: none;
}
.shelf-card {
  width: 152px;
  flex-shrink: 0;
  cursor: pointer;
}
.shelf-card.video-card {
  width: 240px;
}
.shelf-card:active {
  transform: scale(0.97);
}
.shelf-card-art {
  width: 152px;
  height: 152px;
  border-radius: 12px;
  object-fit: cover;
  background: var(--surface-elev);
  display: block;
}
.shelf-card.video-card .shelf-card-art {
  width: 240px;
  height: 135px;
}
.shelf-card-title {
  font-size: 13.5px;
  font-weight: 600;
  margin-top: 7px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.shelf-card-sub {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* Search Screen */
.search-wrap {
  padding: 8px 20px 14px 20px;
}
.search-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 44px;
  padding: 0 14px;
  border-radius: 14px;
  background: var(--surface-elev);
  border: 1px solid var(--solid-border);
}
.search-bar input {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--text);
  font-size: 16px;
  outline: none;
}
.search-clear-btn {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--glass-pill-bg);
  display: none;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-sub);
}
/* Detail View (Album / Playlist) */
.detail-header {
  padding: 12px 20px 18px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}
.detail-art {
  width: 200px;
  height: 200px;
  border-radius: 14px;
  object-fit: cover;
  background: var(--surface-elev);
  box-shadow: 0 16px 36px rgba(0, 0, 0, 0.45);
}
.detail-title {
  font-size: 21px;
  font-weight: 800;
  margin-top: 16px;
  letter-spacing: -0.3px;
}
.detail-sub {
  font-size: 13px;
  color: var(--text-sub);
  margin-top: 4px;
}
.detail-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  width: 100%;
  max-width: 320px;
}
.detail-action-btn {
  flex: 1;
  height: 44px;
  border-radius: 12px;
  background: var(--surface-elev);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
  color: var(--accent);
  cursor: pointer;
}
.detail-action-btn:active {
  transform: scale(0.97);
}
.song-list {
  padding: 0 16px;
}
/* Floating Bottom Cluster: MiniPlayer + GlassNavBar (FloatingBottomBar.kt / GlassNavBar.kt / MiniPlayer.kt) */
.bottom-scrim {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  height: calc(env(safe-area-inset-bottom, 12px) + 146px);
  background: linear-gradient(0deg, var(--bg) 0%, rgba(9,9,11,0.68) 55%, rgba(9,9,11,0) 100%);
  pointer-events: none;
  z-index: 54;
}
body.light .bottom-scrim {
  background: linear-gradient(0deg, var(--bg) 0%, rgba(246,246,249,0.72) 55%, rgba(246,246,249,0) 100%);
}
.bottom-cluster {
  position: fixed;
  left: 16px;
  right: 16px;
  bottom: calc(env(safe-area-inset-bottom, 10px) + 8px);
  z-index: 60;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}
/* MiniPlayer Pill (MiniPlayer.kt: 56px height, pill shape, 40px art with 8px radius) */
.mini-player {
  pointer-events: auto;
  height: 56px;
  border-radius: 999px;
  padding: 0 14px;
  display: none;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}
.mini-player.visible {
  display: flex;
}
.mini-art {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  object-fit: cover;
  background: var(--surface-elev);
  flex-shrink: 0;
}
.mini-meta {
  flex: 1;
  min-width: 0;
}
.mini-title {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mini-artist {
  font-size: 12px;
  color: var(--text-sub);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mini-controls {
  display: flex;
  align-items: center;
  gap: 2px;
}
.mini-icon-btn {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text);
  cursor: pointer;
}
.mini-icon-btn:active {
  transform: scale(0.88);
}
/* GlassNavBar (GlassNavBar.kt: Left main pill for Home + Library, Right standalone 60px circle for Search) */
.glass-nav-row {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 60px;
}
.nav-main-pill {
  flex: 1;
  height: 60px;
  border-radius: 999px;
  padding: 4px;
  display: flex;
  align-items: center;
  position: relative;
}
.nav-tab {
  flex: 1;
  height: 52px;
  border-radius: 999px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  cursor: pointer;
  color: var(--text-sub);
  font-size: 10px;
  font-weight: 500;
  transition: color 0.18s ease, background 0.18s ease;
}
.nav-tab.active {
  color: var(--text);
  font-weight: 700;
  background: var(--glass-pill-bg);
}
.nav-search-circle {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--text-sub);
  position: relative;
  transition: color 0.18s ease, transform 0.15s ease;
}
.nav-search-circle:active {
  transform: scale(0.93);
}
.nav-search-circle.active {
  color: var(--text);
}
.nav-search-circle.active::after {
  content: "";
  position: absolute;
  inset: 4px;
  border-radius: 50%;
  background: var(--glass-pill-bg);
  z-index: 0;
}
.nav-search-circle svg {
  position: relative;
  z-index: 1;
}
/* Full-Screen Now Playing Sheet (NowPlayingScreen.kt) */
.np-sheet {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: #101116;
  color: #ffffff;
  display: flex;
  flex-direction: column;
  transform: translateY(105%);
  transition: transform 0.34s cubic-bezier(0.22, 1, 0.36, 1);
  overflow: hidden;
}
.np-sheet.open {
  transform: translateY(0);
}
.np-backdrop {
  position: absolute;
  inset: -50px;
  background-size: cover;
  background-position: center;
  filter: blur(60px) saturate(190%) brightness(0.42);
  transform: scale(1.18);
  z-index: 0;
  pointer-events: none;
  transition: background-image 0.5s ease;
}
.np-backdrop-scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(10,10,14,0.30) 0%, rgba(10,10,14,0.52) 50%, rgba(10,10,14,0.78) 100%);
  z-index: 0;
  pointer-events: none;
}
.np-inner {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: calc(env(safe-area-inset-top, 12px) + 8px) 24px calc(env(safe-area-inset-bottom, 14px) + 12px) 24px;
  min-height: 0;
}
/* Dismiss Grabber Handle */
.np-grabber-area {
  width: 100%;
  padding: 4px 0 10px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  flex-shrink: 0;
}
.np-grabber-bar {
  width: 38px;
  height: 5px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.36);
}
/* Collapsed Header Row (shown when Lyrics or Queue is open, matching NowPlayingScreen.kt) */
.np-compact-header {
  display: none;
  align-items: center;
  gap: 12px;
  padding: 4px 0 12px 0;
  flex-shrink: 0;
}
.np-sheet.panel-open .np-compact-header {
  display: flex;
}
.np-compact-art {
  width: 52px;
  height: 52px;
  border-radius: 10px;
  object-fit: cover;
  background: rgba(255, 255, 255, 0.1);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.35);
  flex-shrink: 0;
}
.np-compact-meta {
  flex: 1;
  min-width: 0;
}
.np-compact-title {
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np-compact-artist {
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.65);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np-circle-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #ffffff;
  flex-shrink: 0;
  transition: transform 0.14s ease, background 0.18s ease;
}
.np-circle-btn:active {
  transform: scale(0.90);
}
.np-circle-btn.liked {
  color: var(--accent);
  background: rgba(250, 45, 72, 0.20);
}
/* Stage Area: Artwork OR Lyrics OR Queue */
.np-stage {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  position: relative;
}
.np-artwork-view {
  display: flex;
  flex-direction: column;
  justify-content: center;
  flex: 1;
  min-height: 0;
}
.np-sheet.panel-open .np-artwork-view {
  display: none;
}
.np-art-frame {
  width: 100%;
  max-width: min(330px, 42vh);
  aspect-ratio: 1 / 1;
  margin: 0 auto;
  border-radius: 16px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 24px 50px rgba(0, 0, 0, 0.55);
  transition: transform 0.38s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.38s ease;
}
.np-art-frame.paused {
  transform: scale(0.86);
  box-shadow: 0 12px 26px rgba(0, 0, 0, 0.35);
}
.np-art-frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
/* Title + Artist + Star/More Row below Artwork */
.np-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 24px;
}
.np-title-col {
  flex: 1;
  min-width: 0;
}
.np-title {
  font-size: 21px;
  font-weight: 800;
  letter-spacing: -0.3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np-artist {
  font-size: 16px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.68);
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np-title-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* Lyrics Panel */
.np-lyrics-view {
  display: none;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 2px 32px 2px;
  mask-image: linear-gradient(180deg, transparent 0%, #000 10%, #000 88%, transparent 100%);
  -webkit-mask-image: linear-gradient(180deg, transparent 0%, #000 10%, #000 88%, transparent 100%);
  scrollbar-width: none;
}
.np-lyrics-view::-webkit-scrollbar {
  display: none;
}
.np-lyrics-view.visible {
  display: block;
}
.lyric-line {
  font-size: 25px;
  font-weight: 800;
  line-height: 1.4;
  letter-spacing: -0.3px;
  margin: 20px 0;
  color: rgba(255, 255, 255, 0.30);
  filter: blur(0.4px);
  transform: scale(0.96);
  transform-origin: left center;
  transition: color 0.3s ease, transform 0.3s ease, filter 0.3s ease;
  cursor: pointer;
  word-break: break-word;
}
.lyric-line.active {
  color: #ffffff;
  filter: none;
  transform: scale(1.03);
  text-shadow: 0 2px 18px rgba(255, 255, 255, 0.32);
}
.lyric-instrumental {
  display: flex;
  align-items: center;
  gap: 10px;
  color: rgba(255, 255, 255, 0.65);
}
/* Queue Panel (UpNextQueueView in NowPlayingScreen.kt) */
.np-queue-view {
  display: none;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.np-queue-view.visible {
  display: flex;
}
.queue-pills-row {
  display: flex;
  gap: 10px;
  padding: 4px 0 12px 0;
  flex-shrink: 0;
}
.queue-mode-pill {
  flex: 1;
  height: 40px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.72);
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;
}
.queue-mode-pill.active {
  background: rgba(255, 255, 255, 0.88);
  color: #111114;
}
.queue-list-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: none;
}
.queue-list-scroll::-webkit-scrollbar {
  display: none;
}
.queue-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 6px;
  border-radius: 10px;
  cursor: pointer;
}
.queue-item.current {
  background: rgba(255, 255, 255, 0.14);
}
.queue-item-art {
  width: 44px;
  height: 44px;
  border-radius: 7px;
  object-fit: cover;
  background: rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}
/* Scrubber & Transport Controls */
.np-controls-wrap {
  flex-shrink: 0;
  padding-top: 10px;
}
.scrubber-track-wrap {
  position: relative;
  height: 24px;
  display: flex;
  align-items: center;
}
.scrubber-input {
  width: 100%;
  -webkit-appearance: none;
  appearance: none;
  height: 5px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.24);
  outline: none;
  margin: 0;
}
.scrubber-input::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.45);
}
.time-row {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.56);
  font-variant-numeric: tabular-nums;
  margin-top: 2px;
}
.transport-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 14px 0 12px 0;
  padding: 0 6px;
}
.transport-btn {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  cursor: pointer;
  position: relative;
  transition: transform 0.14s ease, opacity 0.18s ease;
}
.transport-btn:active {
  transform: scale(0.88);
}
.transport-btn.play-pause {
  width: 68px;
  height: 68px;
}
.transport-btn.sub-mode {
  width: 40px;
  height: 40px;
  color: rgba(255, 255, 255, 0.55);
}
.transport-btn.sub-mode.active {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.16);
}
.repeat-one-badge {
  position: absolute;
  top: 5px;
  right: 5px;
  font-size: 9px;
  font-weight: 800;
  line-height: 1;
}
/* Bottom Action Row in Now Playing (Lyrics / CarPlay Output / Queue) */
.np-bottom-bar {
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding-top: 4px;
}
.np-bottom-btn {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.62);
  cursor: pointer;
  position: relative;
  transition: background 0.18s ease, color 0.18s ease, transform 0.14s ease;
}
.np-bottom-btn:active {
  transform: scale(0.90);
}
.np-bottom-btn.active {
  background: rgba(255, 255, 255, 0.20);
  color: #ffffff;
}
.np-bottom-btn.audio-active {
  color: var(--green);
}
/* Equalizer Bars Animation */
.eq-bars {
  display: inline-flex;
  align-items: flex-end;
  gap: 2px;
  width: 14px;
  height: 14px;
}
.eq-bars span {
  flex: 1;
  background: var(--accent);
  border-radius: 1px;
  animation: eqBounce 0.75s infinite ease-in-out alternate;
}
.eq-bars span:nth-child(2) { animation-delay: 0.2s; }
.eq-bars span:nth-child(3) { animation-delay: 0.4s; }
@keyframes eqBounce {
  0% { height: 3px; }
  100% { height: 14px; }
}
</style>
</head>
<body>
<div class="top-scrim"></div>
<!-- Floating Top Bar (FrostedTopBar.kt) -->
<header class="top-bar">
  <div class="top-bar-left">
    <div id="topBackBtn" class="top-back-btn glass-surface" onclick="closeDetailView()" title="戻る">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
        <path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z"/>
      </svg>
    </div>
  </div>
  <div class="top-bar-right">
    <div id="fpsPill" class="fps-pill glass-surface">-- FPS</div>
    <div id="carplayTopBtn" class="carplay-circle-btn glass-surface connected" onclick="toggleIphoneAudio()" title="CarPlay / iPhone 音声出力">
      <!-- Material Rounded DirectionsCar -->
      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
        <path d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"/>
      </svg>
      <span class="carplay-dot"></span>
    </div>
    <div class="theme-pill glass-surface">
      <div id="themeDark" class="theme-seg" onclick="setTheme('DARK')" title="ダーク">
        <!-- Material Rounded DarkMode (Crescent Moon) -->
        <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor">
          <path d="M11.01 3.05C6.51 3.54 3 7.36 3 12c0 4.97 4.03 9 9 9 4.63 0 8.45-3.5 8.95-8 .09-.79-.78-1.42-1.54-.95-.84.52-1.84.82-2.91.82-3.04 0-5.5-2.46-5.5-5.5 0-1.07.3-2.07.82-2.91.47-.76-.16-1.63-.91-1.41z"/>
        </svg>
      </div>
      <div id="themeLight" class="theme-seg" onclick="setTheme('LIGHT')" title="ライト">
        <!-- SfIcons.Brightness (sun.max.fill) -->
        <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0-5a1 1 0 0 1 1 1v2a1 1 0 1 1-2 0V3a1 1 0 0 1 1-1zm0 16a1 1 0 0 1 1 1v2a1 1 0 1 1-2 0v-2a1 1 0 0 1 1-1zm10-7a1 1 0 0 1-1 1h-2a1 1 0 1 1 0-2h2a1 1 0 0 1 1 1zM5 12a1 1 0 0 1-1 1H2a1 1 0 1 1 0-2h2a1 1 0 0 1 1 1zm14.07-6.36a1 1 0 0 1 0 1.41l-1.41 1.41a1 1 0 1 1-1.42-1.41l1.42-1.41a1 1 0 0 1 1.41 0zM7.76 16.24a1 1 0 0 1 0 1.41l-1.42 1.42a1 1 0 1 1-1.41-1.42l1.41-1.41a1 1 0 0 1 1.42 0zm11.31 2.83a1 1 0 0 1-1.41 0l-1.42-1.42a1 1 0 1 1 1.42-1.41l1.41 1.41a1 1 0 0 1 0 1.42zM7.76 7.76a1 1 0 0 1-1.42 0L4.93 6.34a1 1 0 0 1 1.41-1.41l1.42 1.41a1 1 0 0 1 0 1.42z"/>
        </svg>
      </div>
      <div id="themeSystem" class="theme-seg" onclick="setTheme('SYSTEM')" title="システム">
        <!-- SfIcons.Gradient (circle.lefthalf.filled) -->
        <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18V4c4.41 0 8 3.59 8 8s-3.59 8-8 8z"/>
        </svg>
      </div>
    </div>
    <div id="accountCircle" class="account-circle glass-surface">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
      </svg>
    </div>
  </div>
</header>
<!-- iPhone / CarPlay Audio Stream Banner -->
<div id="audioBanner" class="audio-banner glass-surface" onclick="toggleIphoneAudio()">
  <div class="audio-banner-left">
    <div class="audio-banner-icon">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
        <path d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"/>
      </svg>
    </div>
    <div class="audio-banner-text">
      <div id="audioBannerTitle" class="audio-banner-title">iPhone / CarPlay から音声を流す</div>
      <div id="audioBannerSub" class="audio-banner-sub">タップして車載スピーカー再生を有効化</div>
    </div>
  </div>
  <div id="audioBannerBtn" class="audio-banner-action">有効化</div>
</div>
<!-- Main Views -->
<main class="main-content">
  <!-- Home Tab -->
  <section id="tabHome">
    <!-- HomeTitle (ic_logo.xml + BitChord) -->
    <div class="home-brand-row">
      <svg class="home-brand-logo" viewBox="0 0 730 484" fill="currentColor">
        <path d="M6.73729,316.937C20.201,294.159 49.6986,286.649 72.4033,300.174H72.4277C237.603,398.282 510.121,407.302 667.823,359.439C693.147,351.759 719.927,366.055 727.602,391.378C735.276,416.715 720.992,443.477 695.649,451.176C514.618,506.132 213.674,495.514 23.5058,382.622C0.678772,369.103 -6.78757,339.685 6.73729,316.937ZM77.8135,154.865C89.38,136.046 113.982,130.11 132.77,141.676C270.528,226.369 480.519,250.891 643.46,201.43C664.592,195.047 686.911,206.957 693.324,228.052C699.689,249.184 687.774,271.46 666.679,277.886C480.555,334.36 249.169,307.004 90.9707,209.79C72.1827,198.224 66.247,173.628 77.8135,154.865ZM140.542,15.3012C149.722,0.246392 169.428,-4.52761 184.482,4.71335C304.811,78.2128 456.29,94.8593 634.684,54.101C651.874,50.1843 669.01,60.9551 672.927,78.1518C676.862,95.3486 666.134,112.484 648.9,116.401C453.677,161.021 286.219,141.799 151.129,59.2417C136.074,50.0006 131.301,30.3561 140.542,15.3012Z"/>
      </svg>
      <span class="home-brand-text">BitChord</span>
      <span class="home-brand-ver">rinrin.ver</span>
    </div>
    <div id="homeContainer"></div>
  </section>
  <!-- Search Tab -->
  <section id="tabSearch" style="display:none;">
    <div class="section-header" style="padding-top:8px;">
      <div class="section-title" style="font-size:28px;">検索</div>
    </div>
    <div class="search-wrap">
      <div class="search-bar">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="color:var(--text-sub);">
          <circle cx="10.5" cy="10.5" r="6.5"></circle>
          <line x1="15.5" y1="15.5" x2="20.5" y2="20.5"></line>
        </svg>
        <input id="searchInput" type="search" placeholder="曲、アーティスト、アルバム" oninput="onSearchInput(this.value)" onkeydown="if(event.key==='Enter'){this.blur();runSearch(this.value);}">
        <div id="searchClearBtn" class="search-clear-btn" onclick="clearSearch()">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
            <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
          </svg>
        </div>
      </div>
    </div>
    <div id="searchResults"></div>
  </section>
  <!-- Library Tab -->
  <section id="tabLibrary" style="display:none;">
    <div class="section-header" style="padding-top:8px;">
      <div class="section-title" style="font-size:28px;">ライブラリ</div>
    </div>
    <div id="libraryContainer"></div>
  </section>
  <!-- Detail View (Album / Playlist) -->
  <section id="tabDetail" style="display:none;">
    <div id="detailContainer"></div>
  </section>
</main>
<div class="bottom-scrim"></div>
<!-- Floating Bottom Cluster: MiniPlayer + GlassNavBar -->
<div class="bottom-cluster">
  <!-- MiniPlayer Pill (MiniPlayer.kt) -->
  <div id="miniPlayer" class="mini-player glass-surface" onclick="openNowPlaying()">
    <img id="miniArt" class="mini-art" src="" alt="">
    <div class="mini-meta">
      <div id="miniTitle" class="mini-title">再生中の曲はありません</div>
      <div id="miniArtist" class="mini-artist"></div>
    </div>
    <div class="mini-controls" onclick="event.stopPropagation()">
      <div id="miniPlayBtn" class="mini-icon-btn" onclick="onTransportPlayPause()">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
          <path d="M8 5v14l11-7z"/>
        </svg>
      </div>
      <div class="mini-icon-btn" onclick="sendAction({type:'next'})">
        <!-- ic_player_next (fast-forward double triangle) -->
        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
          <path d="M5.5 17.6V6.4c0-.65.72-1.03 1.26-.67l7.9 5.6c.45.32.45.98 0 1.3l-7.9 5.6c-.54.36-1.26-.02-1.26-.63zm9.5 0V6.4c0-.65.72-1.03 1.26-.67l7.9 5.6c.45.32.45.98 0 1.3l-7.9 5.6c-.54.36-1.26-.02-1.26-.63z"/>
        </svg>
      </div>
    </div>
  </div>
  <!-- GlassNavBar (GlassNavBar.kt: Home + Library main pill, Search standalone circle) -->
  <div class="glass-nav-row">
    <div class="nav-main-pill glass-surface">
      <div id="navTab0" class="nav-tab active" onclick="switchTab(0)">
        <!-- BitChordIcons.Home -->
        <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 3.2L3 10.5V20c0 .55.45 1 1 1h5.5v-6.5h5V21H20c.55 0 1-.45 1-1v-9.5L12 3.2z"/>
        </svg>
        <span>ホーム</span>
      </div>
      <div id="navTab2" class="nav-tab" onclick="switchTab(2)">
        <!-- BitChordIcons.TabLibrary -->
        <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
          <rect x="3" y="4" width="3.2" height="16" rx="1.2"></rect>
          <rect x="8.2" y="4" width="3.2" height="16" rx="1.2"></rect>
          <path d="M14.2 4.6l5.6 14.8c.22.58-.07 1.22-.65 1.44l-1.1.42c-.58.22-1.22-.07-1.44-.65L11 5.8c-.22-.58.07-1.22.65-1.44l1.1-.42c.58-.22 1.22.07 1.45.66z"></path>
        </svg>
        <span>ライブラリ</span>
      </div>
    </div>
    <div id="navTab1" class="nav-search-circle glass-surface" onclick="switchTab(1)" title="検索">
      <!-- BitChordIcons.TabSearch -->
      <svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="10.5" cy="10.5" r="6.5"></circle>
        <line x1="15.5" y1="15.5" x2="20.5" y2="20.5"></line>
      </svg>
    </div>
  </div>
</div>
<!-- Full-Screen Now Playing Sheet (NowPlayingScreen.kt) -->
<div id="npSheet" class="np-sheet">
  <div id="npBackdrop" class="np-backdrop"></div>
  <div class="np-backdrop-scrim"></div>
  <div class="np-inner">
    <!-- Dismiss Grabber -->
    <div class="np-grabber-area" onclick="closeNowPlaying()">
      <div class="np-grabber-bar"></div>
    </div>
    <!-- Compact Header (visible when Lyrics or Queue is open) -->
    <div class="np-compact-header">
      <img id="npCompactArt" class="np-compact-art" src="" alt="" onclick="showNowPlayingArtwork()">
      <div class="np-compact-meta" onclick="showNowPlayingArtwork()">
        <div id="npCompactTitle" class="np-compact-title">曲を選択してください</div>
        <div id="npCompactArtist" class="np-compact-artist"></div>
      </div>
      <div id="npCompactLikeBtn" class="np-circle-btn" onclick="sendAction({type:'toggleLike'})">
        <svg id="npCompactStarSvg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polygon points="12 2.6 14.9 8.5 21.4 9.4 16.7 14 17.8 20.5 12 17.4 6.2 20.5 7.3 14 2.6 9.4 9.1 8.5 12 2.6"></polygon>
        </svg>
      </div>
    </div>
    <!-- Stage Area -->
    <div class="np-stage">
      <!-- Artwork View -->
      <div id="npArtworkView" class="np-artwork-view">
        <div id="npArtFrame" class="np-art-frame paused">
          <img id="npArt" src="" alt="">
        </div>
        <div class="np-title-row">
          <div class="np-title-col">
            <div id="npTitle" class="np-title">曲を選択してください</div>
            <div id="npArtist" class="np-artist">BitChord</div>
          </div>
          <div class="np-title-actions">
            <div id="npLikeBtn" class="np-circle-btn" onclick="sendAction({type:'toggleLike'})" title="お気に入り">
              <!-- BitChordIcons.Star -->
              <svg id="npStarSvg" width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polygon points="12 2.6 14.9 8.5 21.4 9.4 16.7 14 17.8 20.5 12 17.4 6.2 20.5 7.3 14 2.6 9.4 9.1 8.5 12 2.6"></polygon>
              </svg>
            </div>
          </div>
        </div>
      </div>
      <!-- Synced Lyrics View -->
      <div id="npLyricsView" class="np-lyrics-view"></div>
      <!-- Up Next Queue View -->
      <div id="npQueueView" class="np-queue-view">
        <div class="queue-pills-row">
          <div id="queueShufflePill" class="queue-mode-pill" onclick="sendAction({type:'toggleShuffle'})" title="シャッフル">
            <!-- BitChordIcons.Shuffle -->
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="17 3.5 20.5 7 17 10.5"></polyline>
              <polyline points="17 13.5 20.5 17 17 20.5"></polyline>
              <path d="M3.5 7h4c2.2 0 3.4 1.2 4.5 3.5 1.1 2.3 2.3 6.5 4.5 6.5h4"></path>
              <path d="M3.5 17h4c1.3 0 2.3-.5 3.1-1.6"></path>
              <path d="M14.9 8.6c.8-1.1 1.8-1.6 3.1-1.6h2.5"></path>
            </svg>
          </div>
          <div id="queueRepeatPill" class="queue-mode-pill" onclick="sendAction({type:'cycleRepeat'})" title="リピート">
            <!-- BitChordIcons.Repeat -->
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="16.5 2.8 19.5 5.8 16.5 8.8"></polyline>
              <path d="M19.5 5.8H8.5C5.74 5.8 3.5 8.04 3.5 10.8v1.2"></path>
              <polyline points="7.5 21.2 4.5 18.2 7.5 15.2"></polyline>
              <path d="M4.5 18.2h11c2.76 0 5-2.24 5-5V12"></path>
            </svg>
          </div>
        </div>
        <div id="npQueueList" class="queue-list-scroll"></div>
      </div>
    </div>
    <!-- Scrubber & Transport Controls -->
    <div id="npControlsWrap" class="np-controls-wrap">
      <div class="scrubber-track-wrap">
        <input id="npScrubber" class="scrubber-input" type="range" min="0" max="1000" value="0" oninput="onScrubInput(this.value)" onchange="onScrubCommit(this.value)">
      </div>
      <div class="time-row">
        <span id="npPosText">0:00</span>
        <span id="npDurText">-0:00</span>
      </div>
      <div class="transport-row">
        <div id="npShuffleBtn" class="transport-btn sub-mode" onclick="sendAction({type:'toggleShuffle'})" title="シャッフル">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="17 3.5 20.5 7 17 10.5"></polyline>
            <polyline points="17 13.5 20.5 17 17 20.5"></polyline>
            <path d="M3.5 7h4c2.2 0 3.4 1.2 4.5 3.5 1.1 2.3 2.3 6.5 4.5 6.5h4"></path>
            <path d="M3.5 17h4c1.3 0 2.3-.5 3.1-1.6"></path>
            <path d="M14.9 8.6c.8-1.1 1.8-1.6 3.1-1.6h2.5"></path>
          </svg>
        </div>
        <div class="transport-btn" onclick="sendAction({type:'prev'})" title="前へ">
          <svg width="34" height="34" viewBox="0 0 24 24" fill="currentColor">
            <path d="M18.5 6.4v11.2c0 .61-.72.99-1.26.63l-7.9-5.6c-.45-.32-.45-.98 0-1.3l7.9-5.6c.54-.36 1.26.02 1.26.67zm-9.5 0v11.2c0 .61-.72.99-1.26.63l-7.9-5.6c-.45-.32-.45-.98 0-1.3l7.9-5.6c.54-.36 1.26.02 1.26.67z"/>
          </svg>
        </div>
        <div id="npPlayBtn" class="transport-btn play-pause" onclick="onTransportPlayPause()" title="再生/一時停止">
          <svg width="46" height="46" viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5v14l11-7z"/>
          </svg>
        </div>
        <div class="transport-btn" onclick="sendAction({type:'next'})" title="次へ">
          <svg width="34" height="34" viewBox="0 0 24 24" fill="currentColor">
            <path d="M5.5 17.6V6.4c0-.65.72-1.03 1.26-.67l7.9 5.6c.45.32.45.98 0 1.3l-7.9 5.6c-.54.36-1.26-.02-1.26-.63zm9.5 0V6.4c0-.65.72-1.03 1.26-.67l7.9 5.6c.45.32.45.98 0 1.3l-7.9 5.6c-.54.36-1.26-.02-1.26-.63z"/>
          </svg>
        </div>
        <div id="npRepeatBtn" class="transport-btn sub-mode" onclick="sendAction({type:'cycleRepeat'})" title="リピート">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="16.5 2.8 19.5 5.8 16.5 8.8"></polyline>
            <path d="M19.5 5.8H8.5C5.74 5.8 3.5 8.04 3.5 10.8v1.2"></path>
            <polyline points="7.5 21.2 4.5 18.2 7.5 15.2"></polyline>
            <path d="M4.5 18.2h11c2.76 0 5-2.24 5-5V12"></path>
          </svg>
          <span id="npRepeatOneBadge" class="repeat-one-badge" style="display:none;">1</span>
        </div>
      </div>
      <!-- Bottom Action Bar (BitChordIcons.LyricsQuote / Audio Output / BitChordIcons.Queue) -->
      <div class="np-bottom-bar">
        <div id="npLyricsBtn" class="np-bottom-btn" onclick="toggleNowPlayingPanel('lyrics')" title="歌詞">
          <!-- BitChordIcons.LyricsQuote -->
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 4.5h16c.83 0 1.5.67 1.5 1.5v10c0 .83-.67 1.5-1.5 1.5H9l-4.5 4v-4H4c-.83 0-1.5-.67-1.5-1.5V6c0-.83.67-1.5 1.5-1.5z"></path>
            <path d="M9.8 8.2c-1 1-1.3 2-.9 3 .3.7 1 1 1.7.7.6-.3.9-.9.6-1.5-.3-.6-.9-.8-1.5-.5"></path>
            <path d="M15 8.2c-1 1-1.3 2-.9 3 .3.7 1 1 1.7.7.6-.3.9-.9.6-1.5-.3-.6-.9-.8-1.5-.5"></path>
          </svg>
        </div>
        <div id="npAudioRouteBtn" class="np-bottom-btn" onclick="toggleIphoneAudio()" title="CarPlay / iPhone 音声出力">
          <!-- SfIcons.SpeakerWave2 / AirPlay Audio -->
          <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
            <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/>
          </svg>
        </div>
        <div id="npQueueBtn" class="np-bottom-btn" onclick="toggleNowPlayingPanel('queue')" title="次に再生">
          <!-- BitChordIcons.Queue -->
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
            <line x1="4" y1="6.5" x2="20" y2="6.5"></line>
            <line x1="4" y1="12" x2="20" y2="12"></line>
            <line x1="4" y1="17.5" x2="15" y2="17.5"></line>
          </svg>
        </div>
      </div>
    </div>
  </div>
</div>
<!-- HTML5 Audio Element for iPhone / CarPlay -->
<audio id="carplayAudio" preload="auto" playsinline webkit-playsinline></audio>
"""
    private const val PART_2 = """<script>
const clientId = 'ios_' + Math.random().toString(36).substring(2, 9);
const audio = document.getElementById('carplayAudio');
const SVG_PLAY_LARGE = '<svg width="46" height="46" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>';
const SVG_PAUSE_LARGE = '<svg width="46" height="46" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6V5zm8 0h4v14h-4V5z"/></svg>';
const SVG_PLAY_MINI = '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>';
const SVG_PAUSE_MINI = '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6V5zm8 0h4v14h-4V5z"/></svg>';
const SVG_MORE_VERT = '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5.5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="18.5" r="1.8"/></svg>';
const SVG_CHEVRON_RIGHT = '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg>';
const SVG_INSTRUMENTAL = '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>';
const SVG_EQ_BARS = '<span class="eq-bars"><span></span><span></span><span></span></span>';
let state = {
  activeOnIphone: false,
  mediaId: null,
  title: '',
  artist: '',
  album: '',
  thumbnailUrl: '',
  isPlaying: false,
  playWhenReady: false,
  buffering: false,
  positionMs: 0,
  durationMs: 0,
  commandSeq: 0,
  seekSeq: 0,
  wantedSeekMs: 0,
  shuffle: false,
  repeatMode: 0,
  currentIndex: 0,
  likeStatus: 'INDIFFERENT',
  themeMode: 'SYSTEM',
  liquidGlass: true,
  keepLyricsControlsVisible: false,
  syncScreen: true,
  listenerName: '',
  listenerAvatarUrl: '',
  homeHash: 0,
  queueHash: 0,
  lyricsId: '',
  detailId: '',
  queue: [],
  lyrics: [],
  home: null,
  library: [],
  detail: null
};
let currentTab = 0;
let npOpen = false;
let npPanel = 'art'; // 'art' | 'lyrics' | 'queue'
let isScrubbing = false;
let lastAppliedSeekSeq = 0;
let currentSeekSeq = 0;
let loadedMediaId = null;
let endedSentForMediaId = null;
let audioUnlocked = false;
let lastAndroidScreenKey = '';
let lastAvatarRendered = '';
let searchDebounce = null;
let activeLyricIdx = -1;
let lyricLineEls = [];
let lastRenderedQueueSig = '';
let lastRenderedQueueIndex = -1;

// High-precision 60fps smooth playhead clock state
let lastRawAudioSec = -1;
let audioAnchorMs = 0;
let audioAnchorPerf = 0;
let remoteAnchorMs = 0;
let remoteAnchorPerf = 0;
let smoothPlayheadMs = 0;
let lastFramePerf = performance.now();

function stepSmoothPlayhead(now) {
  const dt = Math.min(100, Math.max(0, now - lastFramePerf));
  lastFramePerf = now;
  const durMs = getEffectiveDurationMs();

  if (state.activeOnIphone && loadedMediaId === state.mediaId && !isNaN(audio.currentTime)) {
    // iPhoneから音を流している時はiPhone内部の<audio>再生位置がマスター
    const rawMs = (audio.currentTime || 0) * 1000.0;
    smoothPlayheadMs = rawMs;
  } else {
    // Android本体から音を流している時はAndroid側のpositionMsを外挿追従
    if (state.isPlaying && !state.buffering) {
      const target = remoteAnchorMs + Math.max(0.0, now - remoteAnchorPerf);
      const diff = target - (smoothPlayheadMs + dt);
      if (Math.abs(target - smoothPlayheadMs) > 600) {
        smoothPlayheadMs = target;
      } else {
        smoothPlayheadMs += (dt + diff * 0.28);
      }
    } else {
      smoothPlayheadMs = state.positionMs || 0;
    }
  }

  if (durMs > 0 && smoothPlayheadMs > durMs) {
    smoothPlayheadMs = durMs;
  }
  if (smoothPlayheadMs < 0) {
    smoothPlayheadMs = 0;
  }
  return smoothPlayheadMs;
}

// Live FPS Counter & 60fps Lyrics / Scrubber Animation Loop
(function startFpsLoop() {
  let frames = 0;
  let lastTime = performance.now();
  let lastScrubPaint = 0;
  const pill = document.getElementById('fpsPill');
  function tick(now) {
    frames++;
    const elapsed = now - lastTime;
    if (elapsed >= 500) {
      const fps = Math.min(120, Math.round((frames * 1000) / elapsed));
      if (pill) pill.textContent = fps + ' FPS';
      frames = 0;
      lastTime = now;
    }
    const posMs = stepSmoothPlayhead(now);
    if (npOpen && !isScrubbing && state.mediaId) {
      if (npPanel === 'lyrics') {
        updateLyricsHighlight(posMs);
      }
      if (now - lastScrubPaint >= 80) {
        lastScrubPaint = now;
        updateProgressUi(posMs, getEffectiveDurationMs());
      }
    }
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
})();
function proxiedArt(url) {
  if (!url) return '';
  return '/api/artwork?url=' + encodeURIComponent(url);
}
function formatMs(ms) {
  if (!ms || ms < 0 || !isFinite(ms)) return '0:00';
  const totalSec = Math.floor(ms / 1000);
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return m + ':' + (s < 10 ? '0' : '') + s;
}
/**
 * Returns the authoritative track duration in milliseconds.
 * Always prefers the server's MP4 header / metadata duration (`state.durationMs`)
 * over iOS Safari's raw `audio.duration` so fragmented MP4 files never double in length.
 */
function getEffectiveDurationMs() {
  if (state.durationMs && state.durationMs > 0) {
    return state.durationMs;
  }
  if (audio.duration && isFinite(audio.duration) && audio.duration > 0) {
    return Math.floor(audio.duration * 1000);
  }
  return 0;
}
function getEffectivePositionMs() {
  const durMs = getEffectiveDurationMs();
  let posMs = smoothPlayheadMs || 0;
  if (state.activeOnIphone && loadedMediaId === state.mediaId && !isNaN(audio.currentTime)) {
    const rawMs = (audio.currentTime || 0) * 1000.0;
    if (Math.abs(rawMs - posMs) > 300) posMs = rawMs;
  } else if (Math.abs((state.positionMs || 0) - posMs) > 650) {
    posMs = state.positionMs || 0;
  }
  if (durMs > 0 && posMs > durMs) {
    posMs = durMs;
  }
  return Math.max(0, posMs);
}
function sendAction(payload) {
  fetch('/api/action?cid=' + encodeURIComponent(clientId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }).then(() => setTimeout(pollState, 60)).catch(() => {});
}
function unlockIosAudio() {
  if (audioUnlocked) return;
  audioUnlocked = true;
  if (state.mediaId) {
    ensureAudioTrackLoaded(state.mediaId, state.positionMs);
    audio.play().catch(() => {});
  } else {
    audio.play().catch(() => {});
  }
}
function toggleIphoneAudio() {
  unlockIosAudio();
  if (state.activeOnIphone) {
    audio.pause();
    sendAction({ type: 'disableIphoneAudio' });
  } else {
    sendAction({ type: 'enableIphoneAudio' });
    if (state.mediaId) {
      ensureAudioTrackLoaded(state.mediaId, state.positionMs);
      setTimeout(() => { audio.play().catch(() => {}); }, 120);
    }
  }
}
function ensureAudioTrackLoaded(mediaId, startPosMs) {
  if (!mediaId) return;
  const targetSrc = '/api/audio/' + encodeURIComponent(mediaId) + '?cid=' + encodeURIComponent(clientId);
  if (loadedMediaId !== mediaId) {
    loadedMediaId = mediaId;
    endedSentForMediaId = null;
    audio.src = targetSrc;
    audio.load();
    if (startPosMs && startPosMs > 1000) {
      const seekSec = startPosMs / 1000.0;
      const onLoaded = () => {
        try { audio.currentTime = seekSec; } catch (e) {}
        audio.removeEventListener('loadedmetadata', onLoaded);
      };
      audio.addEventListener('loadedmetadata', onLoaded);
    }
  }
}
function onTransportPlayPause() {
  unlockIosAudio();
  if (state.activeOnIphone) {
    if (audio.paused) {
      ensureAudioTrackLoaded(state.mediaId, state.positionMs);
      audio.play().catch(() => {});
      sendAction({ type: 'play', positionMs: Math.floor((audio.currentTime || 0) * 1000) });
    } else {
      audio.pause();
      sendAction({ type: 'pause', positionMs: Math.floor((audio.currentTime || 0) * 1000) });
    }
  } else {
    sendAction({ type: 'togglePlay' });
  }
}
// Audio Element Listeners & Authoritative End-of-Track Guard
audio.addEventListener('ended', () => {
  if (loadedMediaId && endedSentForMediaId !== loadedMediaId) {
    endedSentForMediaId = loadedMediaId;
    reportAudioState(true);
  }
});
audio.addEventListener('play', () => {
  reportAudioState(false);
  updateCarPlayMediaSession();
});
audio.addEventListener('pause', () => {
  reportAudioState(false);
  updateCarPlayMediaSession();
});
audio.addEventListener('timeupdate', () => {
  if (!state.activeOnIphone || !loadedMediaId) return;
  const durMs = getEffectiveDurationMs();
  const rawPosMs = Math.floor((audio.currentTime || 0) * 1000);
  if (rawPosMs < 2000 && endedSentForMediaId === loadedMediaId) {
    endedSentForMediaId = null;
  }
  // If iOS Safari thinks the file has extra blank tail beyond the authoritative duration,
  // end the track cleanly at the true duration so there is zero trailing silence.
  if (!audio.paused && durMs > 5000 && rawPosMs >= durMs - 450) {
    if (endedSentForMediaId !== loadedMediaId) {
      endedSentForMediaId = loadedMediaId;
      audio.pause();
      reportAudioState(true);
      return;
    }
  }
  if (!isScrubbing) {
    updateProgressUi( Math.min(rawPosMs, durMs > 0 ? durMs : rawPosMs), durMs );
    updateLyricsHighlight(rawPosMs);
  }
});
let isReportingAudio = false;
function reportAudioState(ended) {
  if (!state.activeOnIphone || !loadedMediaId || isReportingAudio) return;
  isReportingAudio = true;
  const durMs = getEffectiveDurationMs();
  let rawPosMs = Math.floor((audio.currentTime || 0) * 1000);
  if (performance.now() - remoteAnchorPerf < 2500 && Math.abs(rawPosMs - smoothPlayheadMs) > 2500) {
    rawPosMs = smoothPlayheadMs;
  }
  const posMs = durMs > 0 ? Math.min(rawPosMs, durMs) : rawPosMs;
  const buffering = audio.readyState < 2 && !audio.paused;
  fetch('/api/report?cid=' + encodeURIComponent(clientId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mediaId: loadedMediaId,
      playing: !audio.paused && !ended,
      buffering: buffering,
      positionMs: posMs >= 0 ? posMs : 0,
      durationMs: durMs >= 0 ? durMs : 0,
      ended: !!ended,
      seekSeq: currentSeekSeq
    })
  }).catch(() => {}).finally(() => {
    isReportingAudio = false;
  });
}
setInterval(() => {
  if (state.activeOnIphone && loadedMediaId) {
    const durMs = getEffectiveDurationMs();
    const rawPosMs = Math.floor((audio.currentTime || 0) * 1000);
    if (!audio.paused && durMs > 5000 && rawPosMs >= durMs - 450) {
      if (endedSentForMediaId !== loadedMediaId) {
        endedSentForMediaId = loadedMediaId;
        audio.pause();
        reportAudioState(true);
        return;
      }
    }
    reportAudioState(false);
  }
}, 500);
// Apple CarPlay & iOS Lock Screen MediaSession Integration
function updateCarPlayMediaSession() {
  if (!('mediaSession' in navigator)) return;
  try {
    if (state.title) {
      const artUrl = state.thumbnailUrl ? (window.location.origin + proxiedArt(state.thumbnailUrl)) : '';
      navigator.mediaSession.metadata = new MediaMetadata({
        title: state.title,
        artist: state.artist || 'BitChord',
        album: state.album || 'BitChord',
        artwork: artUrl ? [
          { src: artUrl, sizes: '512x512', type: 'image/jpeg' }
        ] : []
      });
    }
    const playing = state.activeOnIphone ? !audio.paused : state.isPlaying;
    navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
    const durMs = getEffectiveDurationMs();
    if (durMs > 0 && navigator.mediaSession.setPositionState) {
      const durSec = durMs / 1000.0;
      const posSec = Math.min(durSec, Math.max(0, getEffectivePositionMs() / 1000.0));
      navigator.mediaSession.setPositionState({
        duration: durSec,
        playbackRate: playing ? 1.0 : 0.0,
        position: posSec
      });
    }
  } catch (e) {}
}
if ('mediaSession' in navigator) {
  try {
    navigator.mediaSession.setActionHandler('play', () => {
      unlockIosAudio();
      if (state.activeOnIphone) {
        audio.play().catch(() => {});
        sendAction({ type: 'play', positionMs: Math.floor((audio.currentTime || 0) * 1000) });
      } else {
        sendAction({ type: 'togglePlay' });
      }
    });
    navigator.mediaSession.setActionHandler('pause', () => {
      if (state.activeOnIphone) {
        audio.pause();
        sendAction({ type: 'pause', positionMs: Math.floor((audio.currentTime || 0) * 1000) });
      } else {
        sendAction({ type: 'togglePlay' });
      }
    });
    navigator.mediaSession.setActionHandler('previoustrack', () => sendAction({ type: 'prev' }));
    navigator.mediaSession.setActionHandler('nexttrack', () => sendAction({ type: 'next' }));
    navigator.mediaSession.setActionHandler('seekto', (details) => {
      if (details.seekTime != null) {
        const durMs = getEffectiveDurationMs();
        let targetMs = Math.floor(details.seekTime * 1000);
        if (durMs > 0) targetMs = Math.min(targetMs, durMs);
        if (state.activeOnIphone) {
          try { audio.currentTime = targetMs / 1000.0; } catch (e) {}
        }
        sendAction({ type: 'seek', positionMs: targetMs });
      }
    });
  } catch (e) {}
}
// Robust State Polling with deduplication and watchdog timeout
let isPollingState = false;
let pollStateAbort = null;

async function pollState() {
  if (isPollingState) return;
  isPollingState = true;
  try {
    if (pollStateAbort) {
      try { pollStateAbort.abort(); } catch (e) {}
    }
    pollStateAbort = new AbortController();
    const timeoutId = setTimeout(() => {
      try { pollStateAbort.abort(); } catch (e) {}
    }, 1800);

    const url = '/api/state?cid=' + encodeURIComponent(clientId)
      + '&hh=' + encodeURIComponent(state.homeHash || 0)
      + '&qh=' + encodeURIComponent(state.queueHash || 0)
      + '&lid=' + encodeURIComponent(state.lyricsId || '')
      + '&did=' + encodeURIComponent(state.detailId || '');
    const resp = await fetch(url, {
      cache: 'no-store',
      signal: pollStateAbort.signal
    });
    clearTimeout(timeoutId);
    if (resp.ok) {
      const data = await resp.json();
      applyState(data);
    }
  } catch (e) {
  } finally {
    isPollingState = false;
  }
}
setInterval(pollState, 450);
pollState();
function applyState(data) {
  if (!data) return;
  // Guard against transient empty payload
  if (!data.mediaId && state.mediaId && data.isPlaying === false && !data.title) {
    return;
  }
  const prevMediaId = state.mediaId;
  state.activeOnIphone = !!data.activeOnIphone;
  state.mediaId = data.mediaId || null;
  state.title = data.title || '';
  state.artist = data.artist || '';
  state.album = data.album || '';
  state.thumbnailUrl = data.thumbnailUrl || '';
  state.isPlaying = !!data.isPlaying;
  state.playWhenReady = !!data.playWhenReady;
  state.buffering = !!data.buffering;
  state.positionMs = data.positionMs || 0;
  state.durationMs = data.durationMs || 0;

  if (prevMediaId !== state.mediaId) {
    smoothPlayheadMs = state.positionMs;
    remoteAnchorMs = state.positionMs;
    remoteAnchorPerf = performance.now();
    audioAnchorMs = state.positionMs;
    audioAnchorPerf = performance.now();
    lastAppliedSeekSeq = data.seekSeq || 0;
    currentSeekSeq = data.seekSeq || 0;
    if (state.activeOnIphone && state.mediaId) {
      ensureAudioTrackLoaded(state.mediaId, state.positionMs);
    }
  } else {
    remoteAnchorMs = state.positionMs;
    remoteAnchorPerf = performance.now();
    if (!state.activeOnIphone) {
      if (Math.abs(smoothPlayheadMs - state.positionMs) > 1200) {
        smoothPlayheadMs = state.positionMs;
      }
    }
  }
  state.commandSeq = data.commandSeq || 0;
  state.seekSeq = data.seekSeq || 0;
  state.wantedSeekMs = data.wantedSeekMs || 0;
  state.shuffle = !!data.shuffle;
  state.repeatMode = data.repeatMode || 0;
  state.currentIndex = data.currentIndex || 0;
  state.likeStatus = data.likeStatus || 'INDIFFERENT';
  state.themeMode = data.themeMode || 'SYSTEM';
  state.liquidGlass = data.liquidGlass !== false;
  state.keepLyricsControlsVisible = !!data.keepLyricsControlsVisible;
  state.syncScreen = data.syncScreen !== false;
  state.listenerName = data.listenerName || '';
  state.listenerAvatarUrl = data.listenerAvatarUrl || '';
  if (data.queue !== undefined) {
    state.queueHash = data.queueHash || 0;
    state.queue = data.queue || [];
  }
  if (data.lyrics !== undefined) {
    state.lyricsId = data.lyricsId || '';
    state.lyrics = data.lyrics || [];
    activeLyricIdx = -1;
    renderLyrics();
  }
  if (data.home !== undefined) {
    state.homeHash = data.homeHash || 0;
    state.home = data.home;
    state.library = data.library || [];
    renderHome();
    renderLibrary();
  } else if (prevMediaId !== state.mediaId && state.home) {
    renderHome();
  }
  if (data.detail !== undefined) {
    state.detailId = data.detailId || '';
    state.detail = data.detail;
    renderDetail(state.detail);
  }
  // Synchronize <audio> element when iPhone audio routing is active
  if (state.activeOnIphone && state.mediaId) {
    if (loadedMediaId !== state.mediaId) {
      ensureAudioTrackLoaded(state.mediaId, state.wantedSeekMs || state.positionMs);
      if (state.playWhenReady) {
        audio.play().catch(() => {});
      }
    } else {
      if (state.seekSeq !== lastAppliedSeekSeq) {
        lastAppliedSeekSeq = state.seekSeq;
        currentSeekSeq = state.seekSeq;
        endedSentForMediaId = null;
        const durMs = getEffectiveDurationMs();
        const clampedSeekMs = durMs > 0 ? Math.min(state.wantedSeekMs, durMs) : state.wantedSeekMs;
        smoothPlayheadMs = clampedSeekMs;
        audioAnchorMs = clampedSeekMs;
        audioAnchorPerf = performance.now();
        remoteAnchorMs = clampedSeekMs;
        remoteAnchorPerf = performance.now();
        if (Math.abs((audio.currentTime * 1000) - clampedSeekMs) > 250) {
          try { audio.currentTime = clampedSeekMs / 1000.0; } catch (e) {}
        }
        updateProgressUi(clampedSeekMs, durMs);
      }
      if (state.playWhenReady && audio.paused) {
        endedSentForMediaId = null;
        audio.play().catch(() => {});
      } else if (!state.playWhenReady && !audio.paused) {
        audio.pause();
      }
    }
  } else if (!state.activeOnIphone && !audio.paused) {
    audio.pause();
  }
  // Follow Android screen navigation when changed on Android
  if (state.syncScreen && data.androidScreen) {
    const s = data.androidScreen;
    const screenKey = `${'$'}{s.selectedTab}:${'$'}{s.showNowPlaying}:${'$'}{s.lyricsOpen}:${'$'}{s.queueOpen}:${'$'}{s.detailBrowseId}`;
    if (screenKey !== lastAndroidScreenKey) {
      lastAndroidScreenKey = screenKey;
      if (s.selectedTab !== currentTab && !s.detailBrowseId) {
        switchTab(s.selectedTab, true);
      }
      if (s.detailBrowseId && state.detail && state.detail.browseId === s.detailBrowseId) {
        showDetailSection();
      }
      if (s.showNowPlaying !== npOpen) {
        if (s.showNowPlaying) openNowPlaying(true); else closeNowPlaying(true);
      }
    }
  }
  updateChromeUi();
  updatePlayerUi();
  updateCarPlayMediaSession();
}
function updateChromeUi() {
  // Theme & Liquid Glass
  const prefersLight = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches;
  const isLight = state.themeMode === 'LIGHT' || (state.themeMode === 'SYSTEM' && prefersLight);
  document.body.classList.toggle('light', isLight);
  document.body.classList.toggle('no-liquid', !state.liquidGlass);
  document.getElementById('themeDark').classList.toggle('active', state.themeMode === 'DARK');
  document.getElementById('themeLight').classList.toggle('active', state.themeMode === 'LIGHT');
  document.getElementById('themeSystem').classList.toggle('active', state.themeMode === 'SYSTEM');
  // Account Avatar in TopBar
  if (state.listenerAvatarUrl && state.listenerAvatarUrl !== lastAvatarRendered) {
    lastAvatarRendered = state.listenerAvatarUrl;
    document.getElementById('accountCircle').innerHTML = `<img src="${'$'}{proxiedArt(state.listenerAvatarUrl)}" alt="">`;
  }
  // CarPlay Top Button & Audio Banner
  const cpBtn = document.getElementById('carplayTopBtn');
  const banner = document.getElementById('audioBanner');
  const bannerTitle = document.getElementById('audioBannerTitle');
  const bannerSub = document.getElementById('audioBannerSub');
  const bannerBtn = document.getElementById('audioBannerBtn');
  const npRouteBtn = document.getElementById('npAudioRouteBtn');
  if (state.activeOnIphone) {
    cpBtn.classList.add('active');
    banner.classList.add('active');
    npRouteBtn.classList.add('audio-active');
    if (audio.paused && state.playWhenReady) {
      bannerTitle.textContent = 'タップして iPhone 音声再生を開始';
      bannerSub.textContent = 'iOS Safari の自動再生ロックを解除します';
      bannerBtn.textContent = '再生開始';
    } else {
      bannerTitle.textContent = 'iPhone / CarPlay 音声出力中';
      bannerSub.textContent = 'Android とリアルタイム同期中';
      bannerBtn.textContent = '出力中';
    }
  } else {
    cpBtn.classList.remove('active');
    banner.classList.remove('active');
    npRouteBtn.classList.remove('audio-active');
    bannerTitle.textContent = 'iPhone / CarPlay から音声を流す';
    bannerSub.textContent = 'タップして車載スピーカー再生を有効化';
    bannerBtn.textContent = '有効化';
  }
}
function updatePlayerUi() {
  const mini = document.getElementById('miniPlayer');
  if (!state.mediaId) {
    mini.classList.remove('visible');
    return;
  }
  mini.classList.add('visible');
  const artSrc = proxiedArt(state.thumbnailUrl);
  const miniArt = document.getElementById('miniArt');
  if (miniArt.dataset.src !== artSrc) {
    miniArt.dataset.src = artSrc;
    miniArt.src = artSrc;
  }
  document.getElementById('miniTitle').textContent = state.title || '無題';
  document.getElementById('miniArtist').textContent = state.artist || '';
  const playing = state.activeOnIphone ? !audio.paused : state.isPlaying;
  document.getElementById('miniPlayBtn').innerHTML = playing ? SVG_PAUSE_MINI : SVG_PLAY_MINI;
  document.getElementById('npPlayBtn').innerHTML = playing ? SVG_PAUSE_LARGE : SVG_PLAY_LARGE;
  document.getElementById('npArtFrame').classList.toggle('paused', !playing);
  const npArt = document.getElementById('npArt');
  const npCompactArt = document.getElementById('npCompactArt');
  if (npArt.dataset.src !== artSrc) {
    npArt.dataset.src = artSrc;
    npArt.src = artSrc;
    npCompactArt.src = artSrc;
    document.getElementById('npBackdrop').style.backgroundImage = artSrc ? `url("${'$'}{artSrc}")` : 'none';
  }
  document.getElementById('npTitle').textContent = state.title || '';
  document.getElementById('npArtist').textContent = state.artist || '';
  document.getElementById('npCompactTitle').textContent = state.title || '';
  document.getElementById('npCompactArtist').textContent = state.artist || '';
  const liked = state.likeStatus === 'LIKED';
  document.getElementById('npLikeBtn').classList.toggle('liked', liked);
  document.getElementById('npCompactLikeBtn').classList.toggle('liked', liked);
  const starFill = liked ? 'currentColor' : 'none';
  document.getElementById('npStarSvg').setAttribute('fill', starFill);
  document.getElementById('npCompactStarSvg').setAttribute('fill', starFill);
  document.getElementById('npShuffleBtn').classList.toggle('active', state.shuffle);
  document.getElementById('queueShufflePill').classList.toggle('active', state.shuffle);
  const repBtn = document.getElementById('npRepeatBtn');
  const repBadge = document.getElementById('npRepeatOneBadge');
  const queueRepPill = document.getElementById('queueRepeatPill');
  repBtn.classList.toggle('active', state.repeatMode !== 0);
  queueRepPill.classList.toggle('active', state.repeatMode !== 0);
  repBadge.style.display = state.repeatMode === 1 ? 'block' : 'none';
  if (!isScrubbing) {
    const durMs = getEffectiveDurationMs();
    const posMs = getEffectivePositionMs();
    updateProgressUi(posMs, durMs);
    updateLyricsHighlight(posMs);
  }
  renderQueue();
}
function updateProgressUi(posMs, durMs) {
  const scrubber = document.getElementById('npScrubber');
  const ratio = durMs > 0 ? Math.min(1, Math.max(0, posMs / durMs)) : 0;
  scrubber.value = Math.floor(ratio * 1000);
  const pct = (ratio * 100).toFixed(1);
  scrubber.style.background = `linear-gradient(90deg, rgba(255,255,255,0.92) ${'$'}{pct}%, rgba(255,255,255,0.22) ${'$'}{pct}%)`;
  document.getElementById('npPosText').textContent = formatMs(posMs);
  const remMs = durMs > posMs ? (durMs - posMs) : 0;
  document.getElementById('npDurText').textContent = durMs > 0 ? ('-' + formatMs(remMs)) : '0:00';
}
function onScrubInput(val) {
  isScrubbing = true;
  const durMs = getEffectiveDurationMs();
  const previewMs = Math.floor((val / 1000.0) * durMs);
  updateProgressUi(previewMs, durMs);
}
function onScrubCommit(val) {
  const durMs = getEffectiveDurationMs();
  const targetMs = Math.floor((val / 1000.0) * durMs);
  isScrubbing = false;
  smoothPlayheadMs = targetMs;
  audioAnchorMs = targetMs;
  audioAnchorPerf = performance.now();
  remoteAnchorMs = targetMs;
  remoteAnchorPerf = performance.now();
  updateProgressUi(targetMs, durMs);
  if (state.activeOnIphone) {
    try { audio.currentTime = targetMs / 1000.0; } catch (e) {}
  }
  sendAction({ type: 'seek', positionMs: targetMs });
}
// Home Rendering (Recommended 4-row snap carousel + Quick Access 3x3 paged grid + Shelves)
function renderHome() {
  const container = document.getElementById('homeContainer');
  if (!state.home) return;
  let html = '';
  // 1. Recommended 4-row horizontal snap carousel
  const rec = state.home.recommended || [];
  if (rec.length > 0) {
    html += `<div class="section-header"><div class="section-title">おすすめ</div></div>`;
    html += `<div class="rec-pager">`;
    for (let i = 0; i < rec.length; i += 4) {
      const col = rec.slice(i, i + 4);
      html += `<div class="rec-column">`;
      for (const item of col) {
        const isCur = item.videoId && item.videoId === state.mediaId;
        html += `<div class="rec-row ${'$'}{isCur ? 'playing' : ''}" onclick='onShelfItemTap(${'$'}{JSON.stringify(item).replace(/'/g, "&#39;")}, "おすすめ")'>
          <div class="rec-art-wrap">
            <img src="${'$'}{proxiedArt(item.thumbnailUrl)}" loading="lazy" alt="">
            <div class="rec-art-playing">${'$'}{SVG_EQ_BARS}</div>
          </div>
          <div class="rec-meta">
            <div class="rec-title">${'$'}{esc(item.title)}</div>
            <div class="rec-sub">${'$'}{esc(item.subtitle)}</div>
          </div>
          <div class="rec-more">${'$'}{SVG_MORE_VERT}</div>
        </div>`;
      }
      html += `</div>`;
    }
    html += `</div>`;
  }
  // 2. Quick Access 3x3 square grid pages with listener avatar + page dots
  const qa = state.home.quickAccess || [];
  if (qa.length > 0) {
    const pages = [];
    for (let i = 0; i < qa.length; i += 9) {
      pages.push(qa.slice(i, i + 9));
    }
    html += `<div class="qa-header">`;
    if (state.listenerAvatarUrl) {
      html += `<img class="qa-avatar" src="${'$'}{proxiedArt(state.listenerAvatarUrl)}" alt="">`;
    }
    html += `<div class="qa-header-text">`;
    if (state.listenerName) {
      html += `<div class="qa-listener">${'$'}{esc(state.listenerName)}</div>`;
    }
    html += `<div class="section-title" style="padding:0;">クイック アクセス</div></div></div>`;
    html += `<div class="qa-pager" onscroll="onQaScroll(this)">`;
    pages.forEach((page) => {
      html += `<div class="qa-page">`;
      page.forEach((item) => {
        const isCollection = !item.videoId && !!item.browseId;
        html += `<div class="qa-tile" onclick='onShelfItemTap(${'$'}{JSON.stringify(item).replace(/'/g, "&#39;")}, "クイック アクセス")'>
          <img src="${'$'}{proxiedArt(item.thumbnailUrl)}" loading="lazy" alt="">
          <div class="qa-tile-scrim"></div>
          <div class="qa-tile-label-row">
            <div class="qa-tile-title">${'$'}{esc(item.title)}</div>
            ${'$'}{isCollection ? SVG_CHEVRON_RIGHT : ''}
          </div>
        </div>`;
      });
      html += `</div>`;
    });
    html += `</div>`;
    if (pages.length > 1) {
      html += `<div id="qaDots" class="qa-dots">`;
      pages.forEach((_, idx) => {
        html += `<span class="qa-dot ${'$'}{idx === 0 ? 'active' : ''}"></span>`;
      });
      html += `</div>`;
    }
  }
  // 3. Remaining Shelves
  const rem = state.home.remaining || [];
  for (const shelf of rem) {
    html += renderShelfHtml(shelf);
  }
  container.innerHTML = html;
}
function onQaScroll(el) {
  const dotsWrap = document.getElementById('qaDots');
  if (!dotsWrap) return;
  const pageIdx = Math.round(el.scrollLeft / Math.max(1, el.clientWidth));
  const dots = dotsWrap.querySelectorAll('.qa-dot');
  dots.forEach((d, idx) => d.classList.toggle('active', idx === pageIdx));
}
function renderLibrary() {
  const container = document.getElementById('libraryContainer');
  const shelves = state.library || [];
  if (shelves.length === 0) {
    container.innerHTML = `<div style="padding:40px 20px;text-align:center;color:var(--text-sub);font-size:14px;">ライブラリを読み込み中...</div>`;
    return;
  }
  let html = '';
  for (const shelf of shelves) {
    html += renderShelfHtml(shelf);
  }
  container.innerHTML = html;
}
function renderShelfHtml(shelf) {
  const items = shelf.items || [];
  if (items.length === 0) return '';
  let html = `<div class="section-header"><div>`;
  if (shelf.subtitle) html += `<div class="section-sub">${'$'}{esc(shelf.subtitle)}</div>`;
  html += `<div class="section-title">${'$'}{esc(shelf.title)}</div></div></div>`;
  html += `<div class="shelf-scroll">`;
  for (const item of items) {
    const isVideo = !!item.isVideo || shelf.layout === 'VIDEOS';
    html += `<div class="shelf-card ${'$'}{isVideo ? 'video-card' : ''}" onclick='onShelfItemTap(${'$'}{JSON.stringify(item).replace(/'/g, "&#39;")}, ${'$'}{JSON.stringify(shelf.title).replace(/'/g, "&#39;")})'>
      <img class="shelf-card-art" src="${'$'}{proxiedArt(item.thumbnailUrl)}" loading="lazy" alt="">
      <div class="shelf-card-title">${'$'}{esc(item.title)}</div>
      <div class="shelf-card-sub">${'$'}{esc(item.subtitle)}</div>
    </div>`;
  }
  html += `</div>`;
  return html;
}
function onShelfItemTap(item, shelfTitle) {
  unlockIosAudio();
  if (item.videoId) {
    sendAction({
      type: 'playShelfItem',
      title: item.title || '',
      subtitle: item.subtitle || '',
      thumbnailUrl: item.thumbnailUrl || '',
      videoId: item.videoId,
      browseId: item.browseId || '',
      isVideo: !!item.isVideo,
      shelfTitle: shelfTitle || 'おすすめ'
    });
  } else if (item.browseId) {
    openBrowseDetail(item.browseId, item.title, item.subtitle, item.thumbnailUrl);
  }
}
async function openBrowseDetail(browseId, title, subtitle, thumbnailUrl) {
  sendAction({
    type: 'openDetail',
    browseId: browseId,
    title: title || '',
    subtitle: subtitle || '',
    thumbnailUrl: thumbnailUrl || ''
  });
  showDetailSection();
  const container = document.getElementById('detailContainer');
  container.innerHTML = `<div class="detail-header">
    <img class="detail-art" src="${'$'}{proxiedArt(thumbnailUrl)}" alt="">
    <div class="detail-title">${'$'}{esc(title)}</div>
    <div class="detail-sub">${'$'}{esc(subtitle)}</div>
  </div>
  <div style="padding:24px;text-align:center;color:var(--text-sub);font-size:14px;">読み込み中...</div>`;
  try {
    const resp = await fetch('/api/browse?id=' + encodeURIComponent(browseId) + '&cid=' + encodeURIComponent(clientId));
    const data = await resp.json();
    renderDetail({
      browseId: browseId,
      title: title,
      subtitle: subtitle,
      thumbnailUrl: thumbnailUrl,
      songs: data.songs || []
    });
  } catch (e) {}
}
function renderDetail(detail) {
  if (!detail) return;
  const container = document.getElementById('detailContainer');
  const songs = detail.songs || [];
  let html = `<div class="detail-header">
    <img class="detail-art" src="${'$'}{proxiedArt(detail.thumbnailUrl)}" alt="">
    <div class="detail-title">${'$'}{esc(detail.title)}</div>
    <div class="detail-sub">${'$'}{esc(detail.subtitle)}</div>`;
  if (songs.length > 0) {
    const firstSong = songs[0];
    html += `<div class="detail-actions">
      <div class="detail-action-btn" onclick='playSongItem(${'$'}{JSON.stringify(firstSong).replace(/'/g, "&#39;")}, ${'$'}{JSON.stringify(detail.title || "").replace(/'/g, "&#39;")})'>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
        <span>再生</span>
      </div>
    </div>`;
  }
  html += `</div><div class="song-list">`;
  songs.forEach((s, idx) => {
    const isCur = s.videoId && s.videoId === state.mediaId;
    html += `<div class="rec-row ${'$'}{isCur ? 'playing' : ''}" onclick='playSongItem(${'$'}{JSON.stringify(s).replace(/'/g, "&#39;")}, ${'$'}{JSON.stringify(detail.title || "アルバム").replace(/'/g, "&#39;")})'>
      <div style="width:24px;text-align:center;font-size:13px;font-weight:600;color:var(--text-muted);">${'$'}{idx + 1}</div>
      <div class="rec-art-wrap">
        <img src="${'$'}{proxiedArt(s.thumbnailUrl || detail.thumbnailUrl)}" loading="lazy" alt="">
        <div class="rec-art-playing">${'$'}{SVG_EQ_BARS}</div>
      </div>
      <div class="rec-meta">
        <div class="rec-title">${'$'}{esc(s.title)}</div>
        <div class="rec-sub">${'$'}{esc(s.artist)}</div>
      </div>
      <div class="rec-more">${'$'}{SVG_MORE_VERT}</div>
    </div>`;
  });
  html += `</div>`;
  container.innerHTML = html;
}
function playSongItem(song, shelfTitle) {
  unlockIosAudio();
  sendAction({
    type: 'playSong',
    videoId: song.videoId,
    title: song.title || '',
    artist: song.artist || '',
    album: song.album || '',
    thumbnailUrl: song.thumbnailUrl || '',
    isVideo: !!song.isVideo,
    shelfTitle: shelfTitle || '検索'
  });
}
// Search
function onSearchInput(q) {
  document.getElementById('searchClearBtn').style.display = q && q.length > 0 ? 'flex' : 'none';
  clearTimeout(searchDebounce);
  if (!q || q.trim().length === 0) {
    document.getElementById('searchResults').innerHTML = '';
    return;
  }
  searchDebounce = setTimeout(() => runSearch(q), 340);
}
function clearSearch() {
  const input = document.getElementById('searchInput');
  input.value = '';
  document.getElementById('searchClearBtn').style.display = 'none';
  document.getElementById('searchResults').innerHTML = '';
}
async function runSearch(q) {
  if (!q || !q.trim()) return;
  const box = document.getElementById('searchResults');
  box.innerHTML = `<div style="padding:24px;text-align:center;color:var(--text-sub);font-size:14px;">検索中...</div>`;
  try {
    const resp = await fetch('/api/search?q=' + encodeURIComponent(q.trim()) + '&cid=' + encodeURIComponent(clientId));
    const data = await resp.json();
    let html = '';
    const songs = data.songs || [];
    const cols = data.collections || [];
    if (songs.length > 0) {
      html += `<div class="section-header"><div class="section-title">曲</div></div><div class="song-list">`;
      for (const s of songs) {
        const isCur = s.videoId && s.videoId === state.mediaId;
        html += `<div class="rec-row ${'$'}{isCur ? 'playing' : ''}" onclick='playSongItem(${'$'}{JSON.stringify(s).replace(/'/g, "&#39;")}, "検索")'>
          <div class="rec-art-wrap">
            <img src="${'$'}{proxiedArt(s.thumbnailUrl)}" loading="lazy" alt="">
            <div class="rec-art-playing">${'$'}{SVG_EQ_BARS}</div>
          </div>
          <div class="rec-meta">
            <div class="rec-title">${'$'}{esc(s.title)}</div>
            <div class="rec-sub">${'$'}{esc(s.artist)}</div>
          </div>
          <div class="rec-more">${'$'}{SVG_MORE_VERT}</div>
        </div>`;
      }
      html += `</div>`;
    }
    if (cols.length > 0) {
      html += renderShelfHtml({ title: 'アルバム・プレイリスト', subtitle: '', items: cols });
    }
    box.innerHTML = html || `<div style="padding:24px;text-align:center;color:var(--text-sub);font-size:14px;">結果が見つかりませんでした</div>`;
  } catch (e) {
    box.innerHTML = `<div style="padding:24px;text-align:center;color:var(--text-sub);font-size:14px;">検索エラー</div>`;
  }
}
// Synced Lyrics & Queue inside Now Playing (Phrase-by-phrase / 区切り単位の表示)
function renderLyrics() {
  const box = document.getElementById('npLyricsView');
  const lines = state.lyrics || [];
  lyricLineEls = [];
  if (lines.length === 0) {
    box.innerHTML = `<div style="padding:70px 16px;text-align:center;color:rgba(255,255,255,0.45);font-size:16px;font-weight:600;">歌詞はありません</div>`;
    return;
  }
  let html = '';
  lines.forEach((l, idx) => {
    const text = (l.text || '').trim();
    if (!text) {
      html += `<div class="lyric-line lyric-instrumental" id="lyric_${'$'}{idx}" onclick="seekFromLyric(${'$'}{l.timeMs})">${'$'}{SVG_INSTRUMENTAL}</div>`;
    } else {
      html += `<div class="lyric-line" id="lyric_${'$'}{idx}" onclick="seekFromLyric(${'$'}{l.timeMs})">${'$'}{esc(text)}</div>`;
    }
  });
  box.innerHTML = html;
  lines.forEach((_, idx) => {
    lyricLineEls[idx] = document.getElementById('lyric_' + idx);
  });
  activeLyricIdx = -1;
  updateLyricsHighlight(getEffectivePositionMs());
}
function seekFromLyric(timeMs) {
  if (timeMs == null || timeMs < 0) return;
  smoothPlayheadMs = timeMs;
  audioAnchorMs = timeMs;
  audioAnchorPerf = performance.now();
  remoteAnchorMs = timeMs;
  remoteAnchorPerf = performance.now();
  if (state.activeOnIphone) {
    try { audio.currentTime = timeMs / 1000.0; } catch (e) {}
  }
  sendAction({ type: 'seek', positionMs: timeMs });
}
function updateLyricsHighlight(posMs) {
  if (!npOpen || npPanel !== 'lyrics') return;
  const lines = state.lyrics || [];
  if (lines.length === 0) return;
  let idx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (posMs >= lines[i].timeMs) idx = i; else break;
  }
  if (idx !== activeLyricIdx) {
    if (activeLyricIdx >= 0 && lyricLineEls[activeLyricIdx]) {
      lyricLineEls[activeLyricIdx].classList.remove('active');
    }
    activeLyricIdx = idx;
    const curEl = idx >= 0 ? lyricLineEls[idx] : null;
    if (curEl) {
      curEl.classList.add('active');
      curEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }
}
function renderQueue() {
  if (!npOpen || npPanel !== 'queue') return;
  const box = document.getElementById('npQueueList');
  const q = state.queue || [];
  const sig = q.map((item) => `${'$'}{item.index}:${'$'}{item.videoId}:${'$'}{item.thumbnailUrl || ''}:${'$'}{item.durationMs || 0}`).join('|');

  if (sig !== lastRenderedQueueSig || (box.children.length === 0 && q.length > 0)) {
    lastRenderedQueueSig = sig;
    lastRenderedQueueIndex = state.currentIndex;
    let html = '';
    for (const item of q) {
      const isCur = item.index === state.currentIndex;
      html += `<div class="queue-item ${'$'}{isCur ? 'current' : ''}" data-qidx="${'$'}{item.index}" data-dur="${'$'}{formatMs(item.durationMs)}" onclick="sendAction({type:'playQueueIndex', index:${'$'}{item.index}})">
        <img class="queue-item-art" src="${'$'}{proxiedArt(item.thumbnailUrl)}" decoding="async" alt="">
        <div class="rec-meta">
          <div class="rec-title" style="color:#fff;">${'$'}{esc(item.title)}</div>
          <div class="rec-sub" style="color:rgba(255,255,255,0.62);">${'$'}{esc(item.artist)}</div>
        </div>
        <div class="queue-item-right">${'$'}{isCur ? SVG_EQ_BARS : `<div style="font-size:12px;color:rgba(255,255,255,0.5);font-variant-numeric:tabular-nums;">${'$'}{formatMs(item.durationMs)}</div>`}</div>
      </div>`;
    }
    box.innerHTML = html;
    return;
  }

  if (state.currentIndex !== lastRenderedQueueIndex) {
    lastRenderedQueueIndex = state.currentIndex;
    const rows = box.querySelectorAll('.queue-item');
    rows.forEach((row) => {
      const rIdx = parseInt(row.getAttribute('data-qidx'), 10);
      const isCur = rIdx === state.currentIndex;
      row.classList.toggle('current', isCur);
      const rightEl = row.querySelector('.queue-item-right');
      if (rightEl) {
        const durText = row.getAttribute('data-dur') || '0:00';
        rightEl.innerHTML = isCur
          ? SVG_EQ_BARS
          : `<div style="font-size:12px;color:rgba(255,255,255,0.5);font-variant-numeric:tabular-nums;">${'$'}{durText}</div>`;
      }
    });
  }
}
// Navigation & Sheet Helpers
function switchTab(tabIdx, fromAndroid) {
  currentTab = tabIdx;
  document.getElementById('tabHome').style.display = tabIdx === 0 ? 'block' : 'none';
  document.getElementById('tabSearch').style.display = tabIdx === 1 ? 'block' : 'none';
  document.getElementById('tabLibrary').style.display = tabIdx === 2 ? 'block' : 'none';
  document.getElementById('tabDetail').style.display = 'none';
  document.getElementById('topBackBtn').classList.remove('visible');
  document.getElementById('navTab0').classList.toggle('active', tabIdx === 0);
  document.getElementById('navTab1').classList.toggle('active', tabIdx === 1);
  document.getElementById('navTab2').classList.toggle('active', tabIdx === 2);
  if (!fromAndroid && state.syncScreen) {
    sendAction({ type: 'selectTab', tab: tabIdx });
  }
}
function showDetailSection() {
  document.getElementById('tabHome').style.display = 'none';
  document.getElementById('tabSearch').style.display = 'none';
  document.getElementById('tabLibrary').style.display = 'none';
  document.getElementById('tabDetail').style.display = 'block';
  document.getElementById('topBackBtn').classList.add('visible');
}
function closeDetailView() {
  switchTab(currentTab);
}
function openNowPlaying(fromAndroid) {
  npOpen = true;
  document.getElementById('npSheet').classList.add('open');
  if (!fromAndroid && state.syncScreen) {
    sendAction({ type: 'setNowPlaying', open: true });
  }
}
function closeNowPlaying(fromAndroid) {
  npOpen = false;
  document.getElementById('npSheet').classList.remove('open');
  if (!fromAndroid && state.syncScreen) {
    sendAction({ type: 'setNowPlaying', open: false });
  }
}
function showNowPlayingArtwork() {
  toggleNowPlayingPanel('art');
}
function toggleNowPlayingPanel(mode) {
  if (npPanel === mode) {
    npPanel = 'art';
  } else {
    npPanel = mode;
  }
  const sheet = document.getElementById('npSheet');
  sheet.classList.toggle('panel-open', npPanel !== 'art');
  document.getElementById('npLyricsView').classList.toggle('visible', npPanel === 'lyrics');
  document.getElementById('npQueueView').classList.toggle('visible', npPanel === 'queue');
  document.getElementById('npLyricsBtn').classList.toggle('active', npPanel === 'lyrics');
  document.getElementById('npQueueBtn').classList.toggle('active', npPanel === 'queue');
  if (npPanel === 'lyrics') {
    activeLyricIdx = -1;
    updateLyricsHighlight(getEffectivePositionMs());
  } else if (npPanel === 'queue') {
    renderQueue();
  }
}
function setTheme(mode) {
  state.themeMode = mode;
  updateChromeUi();
  sendAction({ type: 'setTheme', mode: mode });
}
function esc(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
</script>
</body>
</html>
"""
}
