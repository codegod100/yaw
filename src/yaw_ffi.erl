-module(yaw_ffi).
-export([http_get/1, lookup_env/1, url_encode/1]).

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
