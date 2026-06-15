-module(yaw_ffi).
-export([http_get/1, lookup_env/1, url_encode/1, fetch_bsky_profile/1, fetch_bsky_posts/1]).

http_get(Url) ->
    application:ensure_all_started(inets),
    application:ensure_all_started(ssl),
    Request = {unicode:characters_to_list(Url), [{"accept", "application/json"}]},
    HTTPOptions = [{timeout, 5000}],
    Options = [{body_format, binary}],
    case httpc:request(get, Request, HTTPOptions, Options) of
        {ok, {{_, 200, _}, _Headers, Body}} ->
            {ok, unicode:characters_to_binary(Body)};
        _ ->
            {error, nil}
    end.

lookup_env(Key) ->
    case os:getenv(unicode:characters_to_list(Key)) of
        false ->
            lookup_dotenv(Key);
        Value ->
            {ok, unicode:characters_to_binary(Value)}
    end.

lookup_dotenv(Key) ->
    case file:read_file(".env") of
        {ok, Contents} ->
            KeyBin = unicode:characters_to_binary(Key),
            case find_env_value(binary:split(Contents, <<"\n">>, [global]), KeyBin) of
                {ok, Value} -> {ok, unicode:characters_to_binary(string:trim(Value))};
                error -> {error, nil}
            end;
        _ ->
            {error, nil}
    end.

find_env_value([Line | Rest], Key) ->
    Trimmed = string:trim(Line),
    case Trimmed of
        <<>> ->
            find_env_value(Rest, Key);
        <<$#, _/binary>> ->
            find_env_value(Rest, Key);
        _ ->
            case binary:split(Trimmed, <<"=">>) of
                [Key, Value] -> {ok, Value};
                [_Other, _Value] -> find_env_value(Rest, Key);
                _ -> find_env_value(Rest, Key)
            end
    end;
find_env_value([], _Key) ->
    error.

url_encode(Value) ->
    unicode:characters_to_binary(
        uri_string:quote(unicode:characters_to_binary(Value))
    ).

fetch_bsky_profile(Handle) ->
    Command =
        "python3 -c "
        ++ shell_quote(profile_python())
        ++ " "
        ++ shell_quote(unicode:characters_to_binary(Handle)),
    case os:cmd(Command) of
        [] ->
            {error, nil};
        Output ->
            case split_trimmed_lines(unicode:characters_to_binary(Output)) of
                [Line | _] ->
                    case decode_fields(Line, 2) of
                        [ProfileHandle, DisplayName] -> {ok, {ProfileHandle, DisplayName}};
                        _ -> {error, nil}
                    end;
                _ ->
                    {error, nil}
            end
    end.

fetch_bsky_posts(Handle) ->
    Command =
        "python3 -c "
        ++ shell_quote(posts_python())
        ++ " "
        ++ shell_quote(unicode:characters_to_binary(Handle)),
    Output = os:cmd(Command),
    [decode_post(Line) || Line <- split_trimmed_lines(unicode:characters_to_binary(Output))].

decode_post(Line) ->
    case decode_fields(Line, 5) of
        [Uri, Text, IndexedAt, Handle, DisplayName] ->
            {Uri, Text, IndexedAt, Handle, DisplayName};
        _ ->
            {<<>>, <<>>, <<>>, <<>>, <<>>}
    end.

decode_fields(Line, Count) ->
    Parts = binary:split(Line, <<"\t">>, [global]),
    case length(Parts) of
        Count ->
            [base64:decode(Part) || Part <- Parts];
        _ ->
            []
    end.

split_trimmed_lines(Binary) ->
    [Line || Line <- binary:split(Binary, <<"\n">>, [global]), Line =/= <<>>].

shell_quote(Value) ->
    Binary = unicode:characters_to_binary(Value),
    "'" ++ binary_to_list(binary:replace(Binary, <<"'">>, <<"'\\''">>, [global])) ++ "'".

profile_python() ->
    "import base64, json, sys, urllib.request, urllib.parse\n"
    "handle = sys.argv[1]\n"
    "url = 'https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=' + urllib.parse.quote(handle)\n"
    "with urllib.request.urlopen(url, timeout=10) as r:\n"
    "    data = json.load(r)\n"
    "vals = [data.get('handle', handle), data.get('displayName', '')]\n"
    "print('\\t'.join(base64.b64encode(v.encode('utf-8')).decode('ascii') for v in vals))\n".

posts_python() ->
    "import base64, json, sys, urllib.request, urllib.parse\n"
    "handle = sys.argv[1]\n"
    "url = 'https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=' + urllib.parse.quote(handle) + '&limit=4'\n"
    "with urllib.request.urlopen(url, timeout=10) as r:\n"
    "    data = json.load(r)\n"
    "for item in data.get('feed', []):\n"
    "    post = item.get('post', {})\n"
    "    author = post.get('author', {})\n"
    "    record = post.get('record', {})\n"
    "    vals = [\n"
    "        post.get('uri', ''),\n"
    "        record.get('text', ''),\n"
    "        post.get('indexedAt', '') or record.get('createdAt', ''),\n"
    "        author.get('handle', ''),\n"
    "        author.get('displayName', ''),\n"
    "    ]\n"
    "    print('\\t'.join(base64.b64encode(v.encode('utf-8')).decode('ascii') for v in vals))\n".
