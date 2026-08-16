#pragma once
// NDPR - WinHTTP 客户端（零外部依赖，支持 https）
#include <map>
#include <string>

namespace ndpr {

struct HttpResponse {
    long   status = 0;
    std::string body;
};

class Http {
public:
    // 同步 GET
    static HttpResponse get(std::string const& url, std::map<std::string, std::string> const& headers, int timeoutSec);
    // 同步 POST（json 体）
    static HttpResponse postJson(std::string const& url, std::string const& jsonBody,
                                 std::map<std::string, std::string> const& headers, int timeoutSec);

private:
    static HttpResponse request(std::string const& method, std::string const& url,
                                std::string const& body, std::map<std::string, std::string> const& headers,
                                int timeoutSec);
};

} // namespace ndpr
