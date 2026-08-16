// NDPR - WinHTTP 实现（BDS 为 Windows 环境，零第三方依赖）
#include "Http.h"

#include <windows.h>
#include <winhttp.h>

#include <sstream>

#pragma comment(lib, "winhttp.lib")

namespace ndpr {

namespace {

std::string wstringToUtf8(std::wstring const& w) {
    if (w.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string out(size, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), out.data(), size, nullptr, nullptr);
    return out;
}

std::wstring utf8ToWstring(std::string const& s) {
    if (s.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring out(size, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), out.data(), size);
    return out;
}

struct WinHttpHandle {
    HINTERNET h = nullptr;
    ~WinHttpHandle() {
        if (h) WinHttpCloseHandle(h);
    }
};

} // namespace

HttpResponse Http::request(std::string const& method, std::string const& url, std::string const& body,
                           std::map<std::string, std::string> const& headers, int timeoutSec) {
    HttpResponse result;

    // 解析 URL
    URL_COMPONENTS components{};
    components.dwStructSize = sizeof(components);
    wchar_t scheme[16]{}, hostName[256]{}, urlPath[2048]{};
    components.lpszScheme = scheme;
    components.dwSchemeLength = 15;
    components.lpszHostName = hostName;
    components.dwHostNameLength = 255;
    components.lpszUrlPath = urlPath;
    components.dwUrlPathLength = 2047;

    std::wstring wurl = utf8ToWstring(url);
    if (!WinHttpCrackUrl(wurl.c_str(), (DWORD)wurl.size(), 0, &components)) {
        result.status = -1;
        return result;
    }
    bool isHttps = components.nScheme == INTERNET_SCHEME_HTTPS;
    unsigned short port = components.nPort;

    WinHttpHandle session(WinHttpOpen(L"NDPR/2.1", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME,
                                      WINHTTP_NO_PROXY_BYPASS, 0));
    if (!session.h) {
        result.status = -2;
        return result;
    }
    if (timeoutSec > 0) {
        DWORD timeoutMs = (DWORD)timeoutSec * 1000;
        WinHttpSetTimeouts(session.h, timeoutMs, timeoutMs, timeoutMs, timeoutMs);
    }

    WinHttpHandle connect(WinHttpConnect(session.h, hostName, port, 0));
    if (!connect.h) {
        result.status = -3;
        return result;
    }

    std::wstring wpath = urlPath;
    if (components.dwUrlPathLength == 0) wpath = L"/";

    DWORD flags = isHttps ? WINHTTP_FLAG_SECURE : 0;
    WinHttpHandle req(WinHttpOpenRequest(connect.h, utf8ToWstring(method).c_str(), wpath.c_str(), nullptr,
                                         WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags));
    if (!req.h) {
        result.status = -4;
        return result;
    }

    // 请求头
    std::wstring headerStr;
    for (auto const& [k, v] : headers) {
        headerStr += utf8ToWstring(k) + L": " + utf8ToWstring(v) + L"\r\n";
    }
    if (!body.empty() && headers.find("Content-Type") == headers.end()) {
        headerStr += L"Content-Type: application/json\r\n";
    }

    BOOL sent = WinHttpSendRequest(req.h, headerStr.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : headerStr.c_str(),
                                   headerStr.empty() ? 0 : (DWORD)headerStr.size(),
                                   body.empty() ? WINHTTP_NO_REQUEST_DATA : (LPVOID)body.data(),
                                   (DWORD)body.size(), (DWORD)body.size(), 0);
    if (!sent) {
        result.status = -5;
        return result;
    }
    if (!WinHttpReceiveResponse(req.h, nullptr)) {
        result.status = -6;
        return result;
    }

    // 状态码
    DWORD statusCode = 0;
    DWORD size = sizeof(statusCode);
    WinHttpQueryHeaders(req.h, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &statusCode, &size, WINHTTP_NO_HEADER_INDEX);
    result.status = (long)statusCode;

    // 响应体
    std::stringstream ss;
    DWORD available = 0;
    do {
        if (!WinHttpQueryDataAvailable(req.h, &available)) break;
        if (available == 0) break;
        std::string buf(available, '\0');
        DWORD read = 0;
        if (!WinHttpReadData(req.h, buf.data(), available, &read)) break;
        if (read > 0) ss.write(buf.data(), read);
    } while (available > 0);
    result.body = ss.str();
    return result;
}

HttpResponse Http::get(std::string const& url, std::map<std::string, std::string> const& headers, int timeoutSec) {
    return request("GET", url, {}, headers, timeoutSec);
}

HttpResponse Http::postJson(std::string const& url, std::string const& jsonBody,
                            std::map<std::string, std::string> const& headers, int timeoutSec) {
    return request("POST", url, jsonBody, headers, timeoutSec);
}

} // namespace ndpr
