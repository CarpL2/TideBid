local count = redis.call('INCR', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])

if count == 1 or ttl < 0 then
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
    ttl = tonumber(ARGV[1])
end

if count <= tonumber(ARGV[2]) then
    return -1
end

if ttl < 1 then
    return 1
end

return ttl
