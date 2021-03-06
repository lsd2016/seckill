package org.seckill.service.impl;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.MapUtils;
import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessKilledDao;
import org.seckill.dao.cache.RedisDao;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExecution;
import org.seckill.entity.Seckill;
import org.seckill.entity.SuccessKilled;
import org.seckill.enums.SeckillStateEnum;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
/**
 * spring提供的注解
 * @component 代表所有的组件 统称Spring组件实例，如果知道具体的组件类型 则使用@Service、@Dao/@Controller * 
 *
 */

@Service 
public class SeckillServiceImpl implements SeckillService {
	private Logger logger=LoggerFactory.getLogger(this.getClass());
	
	@Autowired //主动获取实例注入
	private SeckillDao seckillDao;
	@Autowired
	private SuccessKilledDao successKilledDao;
	
	@Autowired
	private RedisDao redisDao;
	
	
	//md5盐值字符串，用于混淆md5;
	private final String salt="jnqw&o4ut922v#y54vq34U#*mn4v";

	public List<Seckill> getSeckillList() {
		return seckillDao.queryAll(0, 4);
	}

	public Seckill getById(long seckillId) {
		return seckillDao.queryById(seckillId);
	}

	public Exposer exportSeckillUrl(long seckillId) {
		//优化点：缓存优化（用Redis缓存起来，降低数据库访问压力）
		//通过超时来维护一致性。
		/**
		 * get from cache
		 * if null
		 * 		get db
		 * else
		 *      put cache
		 * locgoin
		 */
		//1:访问redis
		Seckill seckill=redisDao.getSeckill(seckillId);
		if(seckill==null)
		{
			//2:访问数据库
			seckill=seckillDao.queryById(seckillId);
			if(seckill==null)
			{
				return new Exposer(false,seckillId);
			}
			else
			{
				redisDao.putSeckill(seckill);
			}
		}
		
		
		/*Seckill seckill=seckillDao.queryById(seckillId);
		if(seckill==null)
		{
			return new Exposer(false,seckillId);
		}*/
		
		
		Date startTime=seckill.getStartTime();
		Date endTime=seckill.getEndTime();
		//系统当前时间
		Date nowTime=new Date();
		if(nowTime.getTime()<startTime.getTime()||nowTime.getTime()>endTime.getTime())
			return new Exposer(false,seckillId,nowTime.getTime(),startTime.getTime(),endTime.getTime());
		//md5加密：转换特定字符串的过程，不可逆，不希望用户猜到结果；
		String md5=getMD5(seckillId);
		return new Exposer(true,md5,seckillId);
	}

	private String getMD5(long seckillId){
		String base=seckillId+"/"+salt;
		String md5=DigestUtils.md5DigestAsHex(base.getBytes()); //通过盐值转化为加密数据
		return md5;
	}
	
	
	
	/**
	 * 使用注解控制事务方法的优点：
	 * 1：开发团队达成一致约定，明确标注事务方法的编程风格
	 * 2：保证事务方法的执行时间尽可能短，不要穿插其他网络操作（RPC/HTTP请求或者），或者剥离到事务方法外部。
	 * 3：不是所有的方法都需要事务，如只有一条修改操作或只读操作不需要事务控制。
	 */
	@Transactional 
	 //秒杀是否成功，成功:减库存，增加明细；失败:抛出异常，事务回滚
	public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5)
			throws SeckillException, RepeatKillException, SeckillCloseException {
		if (md5==null||!md5.equals(getMD5(seckillId)))
        {
            throw new SeckillException("seckill data rewrite");//秒杀数据被重写了
        }
        //执行秒杀逻辑:减库存+增加购买明细
        Date nowTime=new Date();

        /*try{ //优化前
            //减库存
            int updateCount=seckillDao.reduceNumber(seckillId,nowTime);
            if (updateCount<=0)
            {
                //没有更新库存记录，说明秒杀结束
                throw new SeckillCloseException("seckill is closed");
            }else {
                //否则更新了库存，秒杀成功,增加明细
                int insertCount=successKilledDao.insertSuccessKilled(seckillId,userPhone);
                //看是否该明细被重复插入，即用户是否重复秒杀
                if (insertCount<=0)
                {
                    throw new RepeatKillException("seckill repeated");
                }else {
                    //秒杀成功,得到成功插入的明细记录,并返回成功秒杀的信息
                    SuccessKilled successKilled=successKilledDao.queryByIdWithSeckill(seckillId,userPhone);
                    return new SeckillExecution(seckillId,SeckillStateEnum.SUCCESS,successKilled);
                }
            }*/
        
        
        
        //第二个优化点：秒杀操作
        //调整insert
        try{ 
            
          //否则更新了库存，秒杀成功,增加明细
            int insertCount=successKilledDao.insertSuccessKilled(seckillId,userPhone);
            //看是否该明细被重复插入，即用户是否重复秒杀(唯一主键：seckillId,userPhone)
            if (insertCount<=0)
            {
                throw new RepeatKillException("seckill repeated");
            }
            else {
            	//减库存 ，热点商品竞争
                int updateCount=seckillDao.reduceNumber(seckillId,nowTime);
                if (updateCount<=0)
                {
                    //没有更新库存记录，说明秒杀结束 ----rollback
                    throw new SeckillCloseException("seckill is closed");
                }
                else {
                    //秒杀成功,得到成功插入的明细记录,并返回成功秒杀的信息---commit
                    SuccessKilled successKilled=successKilledDao.queryByIdWithSeckill(seckillId,userPhone);
                    return new SeckillExecution(seckillId,SeckillStateEnum.SUCCESS,successKilled);
                }
            }

        }catch (SeckillCloseException e1)
        {
            throw e1;
        }catch (RepeatKillException e2)
        {
            throw e2;
        }catch (Exception e)
        {
            logger.error(e.getMessage(),e);
            //所以编译期异常转化为运行期异常
            throw new SeckillException("seckill inner error :"+e.getMessage());
        }

	}

	/**
	 * 通过java客户端调用存储过程
	 * 开发使用存储过程的秒杀逻辑
	 */
	public SeckillExecution executeSeckillProcedure(long seckillId,
			long userPhone, String md5) throws SeckillException,
			RepeatKillException, SeckillCloseException {
		if (md5==null||!md5.equals(getMD5(seckillId)))
        {
            return new SeckillExecution(seckillId,SeckillStateEnum.DATA_REWRITE);
        }
        //执行秒杀逻辑:减库存+增加购买明细
        Date killTime=new Date();
        Map<String,Object> map=new HashMap<String,Object>();
        map.put("seckillId", seckillId);
        map.put("phone", userPhone);
        map.put("killTime", killTime);
        map.put("result", null);
        //执行存储过程，result被复制
        try{
        	seckillDao.killByProcedure(map);
        	//获取result
        	//此处要在pom.xm,中引入MapUtil用于获取集合内的值
        	
        	int result=MapUtils.getInteger(map,"result",-2);
        	if(result==1)
        	{
        		SuccessKilled sk=successKilledDao.queryByIdWithSeckill(seckillId,userPhone);
                return new SeckillExecution(seckillId,SeckillStateEnum.SUCCESS,sk);
        	}
        	else
        	{
        		return new SeckillExecution(seckillId,SeckillStateEnum.stateOf(result));
        	}
        }
        catch(Exception e)
        {
        	logger.error(e.getMessage(),e);
        	return new SeckillExecution(seckillId,SeckillStateEnum.INNER_ERROR);
        }
		
	}

}
