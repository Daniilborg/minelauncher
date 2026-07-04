# MINELAUNCHER

A small personal Minecraft: Java Edition launcher built on Fabric, for my own
use and as a learning project.

## What it does
- Signs in the account owner via the official Microsoft OAuth device code flow
- Downloads the selected game version + Fabric loader from official Mojang/Fabric sources
- Manages mods, resource packs and shaders (via the Modrinth API)
- Launches the game locally

## Auth
Uses only the standard `XboxLive.signin` scope to obtain a Minecraft session
token for the signed-in account. It does not collect, store, phish or transmit
credentials anywhere — everything runs locally and is used only by the owner.

Azure Application (client) ID: 9f6fecc9-3583-4549-87a0-a735ffc23e56
