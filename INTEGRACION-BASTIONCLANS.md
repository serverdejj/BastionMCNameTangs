# Integración BastionMCClans + BastionMCNameTangs

Versión objetivo: Purpur 1.21.11 / Java 21.

Se añade `%bastionclans_player%` **dentro de BastionMCNameTangs**. Este placeholder se resuelve mediante la API pública del plugin BastionMCClans beta (`getClanName(UUID)` y `getClanPlaceholder(UUID)`). No requiere PlaceholderAPI. No registra todavía una expansión global de PlaceholderAPI para otros plugins.

En `plugins/BastionMCNameTangs/config.yml`:

```yaml
nametag:
  clan: '&c✘'
  line-one: '%prefix%&f%player%&r &8(&7%bastionclans_player%&8)'
```

Con clan: muestra el nombre con el color elegido. Sin clan o sin BastionMCClans: muestra el fallback `nametag.clan`. Se conserva `%clan%` para configuraciones antiguas. No se crean hologramas adicionales.

Sube el código a tu repositorio de **BastionMCNameTangs**, no al de Clans. Compila con el workflow Maven existente; sustituye el JAR anterior de NameTangs, conserva el JAR de Clans y reinicia el servidor.
