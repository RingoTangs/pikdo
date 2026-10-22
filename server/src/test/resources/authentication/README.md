# Historical JDBC Session fixture

`legacy-security-context.ser` was generated before moving UserPrincipal, using JDK 21,
Spring Security 7.1.1 and Java ObjectOutputStream. It contains a SecurityContextImpl
with an authenticated UsernamePasswordAuthenticationToken and ROLE_USER.

The historical principal is the record
`io.github.ringotangs.pikdo.server.identity.security.UserPrincipal(String userId, long authenticatedAt)`
(serialVersionUID 0). Its dummy user ID is `0198a7b2-1234-7000-8000-000000000001` and
its authentication timestamp is `2026-10-22T00:00:00Z`. It contains no credentials,
cookies or real user data. Keep this fixture unchanged to test the old wire format;
serializing the relocated principal would not test upgrade compatibility.
