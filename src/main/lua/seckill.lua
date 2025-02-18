---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by admin.
--- DateTime: 2022/7/26 12:31
---
--先思考需要什么参数
local voucherId=ARGV[1]
local userId=ARGV[2]

local storyKey='seckill:stock:'..voucherId
local orderKey='seckill:order:'..voucherId

--如果库存不足
if (tonumber(redis.call('get',storyKey))<=0) then
    return 1
end
--判断是否已经下单
if (redis.call('sismember',orderKey,userId)==1) then
    return 2
end
--两个条件都通过，扣库存,下单
redis.call('incrby',storyKey,-1)
redis.call('sadd',orderKey,userId)
return 0