package com.dslauncher.data

import java.security.SecureRandom
import java.util.Base64

/**
 * DeepSeek 设备指纹（device_id）池与生成器。
 *
 * 官网在 /users/login、/users/register 等鉴权类请求的 body 里校验 device_id；
 * 所有账号共用同一枚硬编码指纹是风控里最直接的「多号同设备」关联特征。
 *
 * 格式：64 字节 → base64，首字节为版本位（真实指纹因此都以 "B" 开头）。
 * 池内条目来自公开逆向项目维护的真实指纹库（github.com/Fly143/deepseek-free-api
 * 的 app/device_ids.py），是被官网接受过的真实格式；池耗尽或需独占时由
 * [synthetic] 按同一格式合成新指纹。
 *
 * 指纹只存在两处：账号条目（[Account.deviceId]，登录时持久化）与登录请求体。
 * 轮换时机由 [AccountStore] 统一控制（禁言 / Token 失效 / 到期 / 手动）。
 *
 * ## 效果边界（别抱不切实际的期待）
 *
 * - 官网只在 `/users/login`、`/users/register` 这类**鉴权类请求体**里看 `device_id`，
 *   对话请求并不带它 —— 所以指纹只影响「登录时被看成哪台设备」。
 * - 当前 App 的正常路径是 **WebView 登录**（抓 token），那条路根本不发 `device_id`，
 *   真正带有指纹的只有 `DeepSeekWebRepository.login(email, password)` 这条备用直登通道。
 * - 禁言/封禁是官网综合判定（IP、UA、行为频率、内容）的结果：
 *   **更换指纹不保证降低被禁言概率**，它只在「被禁言后换个设备身份重新登录」这件事上有点作用。
 */
object DeviceFingerprints {

    private val random = SecureRandom()

    /** 公开指纹池（48 枚，真实格式）。 */
    private val POOL = listOf(
        "BcRDjHJXyrxAcmTYz828Az+SYYdsmh81HZtcjgNizEf1t0DoIxU60cC1Kj5sx1e0/8RgBPsQtjkQGAhI3MSlfqQ==",
        "BMSYfgBOR0BZeeCm9aH1UrFr0+oOQZP2sGrcGJR5XMR8SZ3nTlvrZzhHJMWfjQUNbCKYzpjmSz8CubVjQ3hidxg==",
        "Bd3cF86OH9kaPHy19Ozmnm8KB4FzN3dxGQdtAKcglROFG63Vik+DgpfP6u+zsuOOXzTzP80QLERXTy4R63jhCrA==",
        "BpHgp3YRoVh0Wt6X9w3C5yDdmvGvM+glfXT8WieHSFH93sEczEQDKzxJIHL9DV09f9s75mtxtRiRiPOAjvy4GUA==",
        "BXrxMKKMa7jIvLG2KJML8BFBUx6/2BSEmNtfzDHPC3BOuWUBalpqQFp14AU/B9avKYXFNnqgkNTN2mGKOptmzCw==",
        "BQxB5T9wY/6UuawWSaZb3pD4hJf8ui0OW9YPzTcfZkGVtkwHQzRuUDoRF/J3OEBLRbVVL4WcDeSeccuDmLYELww==",
        "BAeuwWK47Qh1F5vyPYULR+OTfsUcnEtVxxGM9Nn1WnTnmGa08ItA7RGdySkZ8JvVIHcFs3Kn1gDyITz0lMCpGvA==",
        "BCJKKcJ84SBlwdTt+E1Xxi5oG67VR57FZeI3g+2WkUs7qnd68nVbLA7frn3uV7GMjUP0v1eLW56T4Ygps/q+HNQ==",
        "BTxrebIsUAG7cbYSNkt/2q6NTLpgWYoBYsZgguFZhuCZDkkjkhbbX3+Xe0UGdK6QH/C5d4Uso6xV2dK4lgvPwBA==",
        "BA+UI8uXgxZMsIdXa9wMfoBlx/P0adIOkqNgmzL8qRdzi28AUCwJGDdOuHvTlvPF7z2rcij5GiqUnk4NIBk04CQ==",
        "BK4G9K6xC3Mhjr0bnhVnqGPs/mj5FGwQDz9aL/RHCtgVbO59BMMem6nB1Z8lUcq8deSESynPL43EmaJlo9kRP1A==",
        "BDhIAlAiEpmdFBJtT/awf1BguBBxXBVWvV6W/N7B5+XfdFTJ5YLgIZ3E7KzI15jecgUHabA6MxLQ81WjrMF2s6w==",
        "Bimz8MMcofv3n2KAEyOjRAEmZxnX9mB7jDKf+QTqcGuquSm5NPZtKJq8PHm9/sh7SO6QgnLDaWbv/QE8Dtv8p1w==",
        "Bgbvoft3dHgpRgADBXPOtNv+ClGOJyHNDWbbRMhHJOHjrJnhbcek174wd5PV3L75gsWKlxFaNDmYmDlK3qpbFVw==",
        "BUQ9ILXnQcZ+BvlnDYgmT21RXB11zKeVGGYAuoYVnerWz+2lLhOPAL9QrRDV8EzoXaAVNomASU0pydJzScNlW/A==",
        "BZ9Owa1XvXjrFiay0oCJ4J2MbN7mtj1hnmJWo6RDMOcvNn2kzqAq/nnIROSTMTWDb/Rbok/I9cWtrTBy4OmhSvA==",
        "BDhiQpzudOpdZmvm1bde+GBvz+Mhem7Ce+CnawIX5gFMxiULzT84608fDjzMhxOsGKMiBGe9wZy7fv7xxQHh6rQ==",
        "BD0WqwX3+mWy1vEE5yiB46YB5hv98fVU+hDO5R2qRm5gIksD7IR4gAQEZuBJ2IuXyVU4Tprf0ck5kFKRmebdIGg==",
        "BHG75zVF+cUdorylq0TbHvg6Hhea0gssCdWRhJ0RUrJ9mwXZ9OKJa6HTZXvOlq23FghLQ3yZiiFsMQqYtTSTbzg==",
        "BOfAYO3LZsLsuGv9zdVyxb7pjrX9ipTfW9BgISHO2XUMm/Fah2YZH4rSWrp4jDy6zM06JSQfp2LS+/3XJ3jd98g==",
        "Bf2RTARyPE8E149zCSZEGh/013lK9/2CAYuMpUOLGWYkEiZUV02EY4rtvFdUnMKwHIsWmHQSMHTN5Y/j95FsmZg==",
        "BXvim/STrJOMZJxHwd/Tcwjm4+pNPPhHwa2kQo+NOuIlpl6mM990ogAGVYwENa1FGhT78wPeQngN6WZSoDJdSbA==",
        "BB4rfE018RYl3ECIrmXjMJ2iewPqOBqUin53KBr6oa46D+f2I4P7PZ9si+aNDqagCZB12MutRgz+rUkJy7VQ+bA==",
        "BLFig4tkn7oIm2+9sF51Qethv7IYdJ76ti1kvM9jQdMMHxy8yE/q+ExyH4G/YlWkiWQbPQw3M5bmN38sNQ2UhVg==",
        "BXIZcs36cU0v4nFPIPlipcWp1xuVOS80+b43UDU5sB/sQbPD9Kvzm+4Tt4Ox/hIqiI+C68JTtVGWs/uoQjHoiZg==",
        "BTfpYZetsP756znBXynxyPFSWuLRXE9bv8SiebxOK0cL8ZKuc8Co+1AojX01qA6MtQbyRLbjsk+UeYMJnlKMJdg==",
        "BjND2/+PJlA/VaLTqe3H223F6tmfAkxS4piEgtPqz9VACbux97X6SDFRWbNkMcsmQPtrgY0/Qb6CJkAdJMVY/vw==",
        "BW8bH8tUY9S43852jS2DtK543jFq7ihD4wB0PpVucdpeLf/AQNCOFEfocnSO2Fb9h9552ZCCetBpMGUlTXVsB2g==",
        "BkkDhV2XeTIRnUFUzfmEmjYUH79pegGnefEgja1WvAHWkhYXeESo2704zqAoBc6jhtgNjAG/M9qKeHWpNr5j6Tw==",
        "Bn7ZRZpXzbGYAFT47352MEO2/+Lm4NiJblGRSAA1zSsAE6bylUOJybozdajQFFTcMGqz8f5WEgoFZTG7TY6l0ng==",
        "BYpI8lkcL+acba/4ewBnG1Mp4PYAIFhs8I2iYIsVSPw3aawUfdjlc0OKEQgnCRyCnoihkkTWIgCleCzgGKjUBUQ==",
        "BPSGQcrfyTSTSwLuGZw4+JnkSc2nqYTBgyh0UM0HsN+ACvCVYIeToh+nVsCOfSK1EdOYABuInANCszuRPfZlqAg==",
        "BPI1YZeGotZ1nmgsNZjsT9a/ChjsKVDI4qPwJISBwGTeXTf6NfixUn97OuhWyC9BUlHfyxERypryC9MmvnM+2Vg==",
        "BJJXEZm6sGV9RD1AhTdJw1mJOPplatvYwTcjY2WyyqczERjPVAIx8gAUCydrq8m2qhtHZeVwj/l0o5k5V8aHOvQ==",
        "BY+qNzfOKCGo3Auc30dQHtok0VE+PMPhzBivcZZYFAp+IDrZr+Ep/61KxFeiLsvmmFMqFd6aFr9/LIhvJjSZ/OA==",
        "BKtr8lZAAfVkWWqml2axz4qWSEnMQ13PR5BTjaOAPBEmceOmKY6IGG0wDHZrT8gz/J5A8nVYZzrY1KdCdF4BMSg==",
        "BbC/RLbROJ7v9mDCjKHQ/HCBFEk9wvFF+lk0imoUPuRaaOlI00jdK/d79P1gSjhoSBZdJjWanEEDmzcXOf3Hm8A==",
        "BQlUjMsRbNNAWiDL7t4vNxVB9ad8iXUIwQ26m0IjO59NX+lGNwzar4T7700bh4nXuEi/73jJn4As34GGXJLPp+A==",
        "BlY5qI66GYMK429bMjB54KyEtaNX/LhWFCLTCCbPz5Z/RLyMfx099MVzcVaIO8UXiFMNtfFwzNX5J42JpR284Jw==",
        "BpCdx1RvQuyUzcLM1zfOHIUL/cDfxrtAQcdPjAG+XR/U4xXfGLNfye3fUu+nUOn9TGJd0iIT7IlvyGjTpfgu48Q==",
        "BXBkjThWyzLaafiNco+IYwn8iPCmmjPLFDUWy2ch9gO+IQQUnxqaSl+XyaMYVieIPAlCakXsITmK27PgcUaE9kA==",
        "BGK/NL4vrzjQA+7IqtHVlluZBh99RVGj13wlGtlxk3vsdLTaHV/WeYuM11ytIlXeb7ZuovwqENArmgHmQYYzHeA==",
        "BUpQjaic6V70MaQscBsqzeAwtVEhawjHXZ4cnjlmT5D/cO7sg9NGwNJl1Ei7PTnpG4vu2ty9SqrqvZXmeJs3VuQ==",
        "BQCGM/XVrRB1qMMhS60Ap8EBaK1ymvqY0NJDbntbw0V2Zbzzsh9o1/SqTAStoeIB3IgtzcKTReCR0C3PNfc/Q+A==",
        "BmgZwRzTAoKv7tYtEIZ7cMCysIbEzTb0JF3FvR6iJbBKNKmuN6oYsZfCY5VFWiv7OBQVwW8kypw3kMJ85Zn4HZg==",
        "BHGxFA7SdSgOX9MiJb4U6r1Mzu9YfMnd5iG3USS+wItiTl+yOee1nR1ZBNQIAHIJhXQLGwKC+lGIN9ETEXQupvg==",
        "BHzh1kiRV65YfWIulN0sghenK8WBvGKcFxiaDTvZskdB8QeqsvJ00JC4jTNbFy4SObnxUId0TPQejr1uclhrVjg==",
        "BpUoRpmIdgD4SUWrBuPM0/D74xUyo441UVz2QNxqlMbIm3tzyntmPd2Kyo2bswrD/WCFfYAz0bTqSS0BIKRg/eA==",
    )

    /**
     * 按真实格式合成一枚全新指纹（版本字节 0x04 + 63 随机字节）。
     * 与池内指纹不同，它没有历史使用痕迹，适合「全新设备」语义。
     */
    fun synthetic(): String {
        val buf = ByteArray(64)
        random.nextBytes(buf)
        buf[0] = 0x04
        return Base64.getEncoder().encodeToString(buf)
    }

    /**
     * 取一枚未被占用的指纹。
     *
     * @param exclude 已被占用的指纹（通常是账号池里其他账号的 deviceId）——
     * 多账号绝不共享同一枚指纹，否则风控把它们关联成同一设备。
     * 池内没得挑时退回 [synthetic]。
     */
    fun newFingerprint(exclude: Set<String> = emptySet()): String {
        val free = POOL.filter { it !in exclude }
        return if (free.isNotEmpty()) free[random.nextInt(free.size)] else synthetic()
    }
}
