from pathlib import Path

target = Path("proxy/src/main/java/net/md_5/bungee/connection/InitialHandler.java")
source = target.read_text(encoding="utf-8")

import_anchor = "import java.util.concurrent.CompletableFuture;\n"
if "import java.util.concurrent.TimeUnit;" not in source:
    if import_anchor not in source:
        raise SystemExit("Could not locate CompletableFuture import")
    source = source.replace(import_anchor, import_anchor + "import java.util.concurrent.TimeUnit;\n", 1)

field_anchor = "    private State thisState = State.HANDSHAKE;\n"
field_addition = """    // Cache Mojang profile lookups briefly to avoid one HTTP request per reconnect.
    private static final com.google.common.cache.Cache<String, Boolean> HYBRID_PROFILE_CACHE =
            com.google.common.cache.CacheBuilder.newBuilder().maximumSize(10000)
                    .expireAfterWrite(10, TimeUnit.MINUTES).build();
"""
if "HYBRID_PROFILE_CACHE" not in source:
    if field_anchor not in source:
        raise SystemExit("Could not locate InitialHandler state field")
    source = source.replace(field_anchor, field_anchor + field_addition, 1)

start_marker = "    public void handle(LoginRequest loginRequest) throws Exception\n    {"
end_marker = "    @Override\n    public void handle(final EncryptionResponse encryptResponse)"
start = source.find(start_marker)
end = source.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit("Could not locate LoginRequest handler boundaries")

old_method = source[start:end]
body_start = old_method.find("        if ( !AllowedCharacters.isValidName")
if body_start < 0:
    raise SystemExit("Could not locate existing login validation body")
body = old_method[body_start:]
method_close = body.rfind("    }")
if method_close < 0:
    raise SystemExit("Could not locate LoginRequest handler closing brace")
body = body[:method_close]

replacement = """    public void handle(LoginRequest loginRequest) throws Exception
    {
        Preconditions.checkState( thisState == State.USERNAME && this.loginRequest == null, "Not expecting USERNAME" );
        this.loginRequest = loginRequest;

        // Existing Mojang profiles must prove account ownership through BungeeCord's
        // normal encryption and session-server validation. Unknown names use lobby login.
        if ( !BungeeCord.getInstance().config.isOnlineMode() )
        {
            String profileName = loginRequest.getData().toLowerCase( java.util.Locale.ROOT );
            Boolean cachedPremium = HYBRID_PROFILE_CACHE.getIfPresent( profileName );
            if ( cachedPremium != null )
            {
                onlineMode = cachedPremium;
                continueLoginRequest();
                return;
            }

            String profileUrl = "https://api.mojang.com/users/profiles/minecraft/"
                    + URLEncoder.encode( loginRequest.getData(), "UTF-8" );
            HttpClient.get( profileUrl, ch.getHandle().eventLoop(), ( result, error ) ->
            {
                if ( error != null )
                {
                    bungee.getLogger().log( Level.WARNING, "Could not check Mojang profile for " + loginRequest.getData()
                            + "; refusing offline fallback.", error );
                    disconnect( "§cPremium-Status konnte nicht geprüft werden. Bitte später erneut verbinden." );
                    return;
                }

                boolean premiumProfile = result != null && !result.isBlank();
                HYBRID_PROFILE_CACHE.put( profileName, premiumProfile );
                onlineMode = premiumProfile;
                continueLoginRequest();
            } );
            return;
        }

        onlineMode = true;
        continueLoginRequest();
    }

    private void continueLoginRequest()
    {
""" + body + """    }

"""

# Keep finish() exception handling in one helper so every call site is safe.
source = source[:start] + replacement + source[end:]
source = source.replace("finish();", "finishSafely();")

helper_anchor = "    @Override\n    public void handle(final EncryptionResponse encryptResponse)"
helper = """    private void finishSafely()
    {
        try
        {
            finish();
        } catch ( java.security.GeneralSecurityException ex )
        {
            bungee.getLogger().log( Level.SEVERE, "Could not finish login for " + getName(), ex );
            disconnect( "§cAnmeldung fehlgeschlagen. Bitte erneut versuchen." );
        }
    }

"""
if "private void finishSafely()" not in source:
    if helper_anchor not in source:
        raise SystemExit("Could not locate EncryptionResponse handler for finish helper")
    source = source.replace(helper_anchor, helper + helper_anchor, 1)

target.write_text(source, encoding="utf-8")
print("Hybrid login patch applied with centralized finishSafely() exception handling")
