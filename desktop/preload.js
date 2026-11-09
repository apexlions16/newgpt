// Arayüzün tam ekran isteği (oynatıcı) için küçük köprü.
const { contextBridge } = require("electron");
contextBridge.exposeInMainWorld("gflixDesktop", { platform: process.platform });
