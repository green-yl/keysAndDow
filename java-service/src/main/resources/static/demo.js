// Demo测试脚本
let currentLicense = null;
let currentHwid = generateHwid();
let currentActivationCode = null;

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('hwid').value = currentHwid;
});

// 生成设备ID
function generateHwid() {
    return 'DEMO_' + Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
}

// 生成license.lic文件到用户本地
async function generateLicenseFile(license, hwid) {
    try {
        // 构建许可证文件内容
        const licenseData = {
            version: "1.0",
            alg: license.alg || "Ed25519",
            kid: license.kid,
            hwid: hwid,
            payload: license.payload,
            signature: license.sig,
            generated_at: new Date().toISOString(),
            generated_by: "KeysAndDwd License System"
        };
        
        // 转换为JSON字符串
        const licenseContent = JSON.stringify(licenseData, null, 2);
        
        // 创建Blob对象
        const blob = new Blob([licenseContent], { type: 'application/json' });
        
        // 创建下载链接
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'license.lic';
        a.style.display = 'none';
        
        // 添加到DOM并触发下载
        document.body.appendChild(a);
        a.click();
        
        // 清理
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
        
        console.log('license.lic文件已生成到本地');
        
    } catch (error) {
        console.error('生成license.lic文件失败:', error);
        throw error;
    }
}

// 显示加载状态
function showLoading(show = true) {
    const progressSection = document.getElementById('progressSection');
    progressSection.style.display = show ? 'block' : 'none';
}

// 显示结果
function showResult(elementId, data, isError = false) {
    const element = document.getElementById(elementId);
    const className = isError ? 'status-error' : 'status-success';
    
    let displayText;
    if (typeof data === 'string') {
        displayText = data;
    } else {
        displayText = JSON.stringify(data, null, 2);
    }
    
    element.innerHTML = `<div class="${className}">${displayText}</div>`;
    element.scrollTop = element.scrollHeight;
}

// 步骤1: 生成激活码
async function generateCode() {
    showLoading(true);
    
    try {
        const cardType = document.getElementById('cardType').value;
        
        // 直接获取现有套餐，不再自动创建
        
        // 获取套餐列表
        const plansResponse = await fetch('/api/admin/plans');
        
        if (!plansResponse.ok) {
            throw new Error(`获取套餐失败: ${plansResponse.status} ${plansResponse.statusText}`);
        }
        
        const plans = await plansResponse.json();
        
        console.log('获取到的套餐数据:', plans);
        
        if (!plans || plans.length === 0) {
            throw new Error('没有可用的套餐，请先在管理后台创建套餐');
        }
        
        // 找到对应的套餐
        const planName = cardType === 'week' ? '周卡' : '月卡';
        let plan = plans.find(p => p.name === planName);
        
        // 如果没有找到指定套餐，使用第一个可用套餐
        if (!plan) {
            plan = plans[0];
            console.warn(`未找到${planName}套餐，使用 ${plan.name} 套餐代替`);
        }
        
        // 创建激活码
        const createData = {
            plan_id: plan.id,
            issue_limit: 1,
            exp_at: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 19), // 30天后过期，修正时间格式
            note: `Demo测试激活码 - ${plan.name}`, // 使用实际套餐名
            count: 1
        };
        
        const response = await fetch('/api/admin/codes/batch', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(createData)
        });
        
        const result = await response.json();
        
        if (result.ok && result.codes && result.codes.length > 0) {
            const code = result.codes[0].code;
            currentActivationCode = code; // 设置全局变量
            document.getElementById('activationCode').value = code;
            
            showResult('generatedCode', {
                success: true,
                message: '激活码生成成功！',
                code: code,
                type: plan.name,
                duration: plan.durationHours + '小时',
                quota: plan.initQuota + '次下载'
            });
        } else {
            throw new Error(result.error || '生成激活码失败');
        }
        
    } catch (error) {
        console.error('生成激活码失败:', error);
        showResult('generatedCode', 'ERROR: ' + error.message, true);
    } finally {
        showLoading(false);
    }
}

// 步骤2: 激活许可证
async function activateLicense() {
    showLoading(true);
    
    try {
        const code = document.getElementById('activationCode').value;
        const hwid = document.getElementById('hwid').value;
        const clientId = document.getElementById('clientId').value || undefined;
        
        if (!code) {
            throw new Error('请输入激活码');
        }
        
        // 更新全局变量
        currentActivationCode = code;
        
        const activateData = {
            code: code,
            hwid: hwid,
            sub: clientId,
            client: {
                ver: '1.0.0',
                os: 'Windows_Demo',
                tz: '+08:00'
            }
        };
        
        const response = await fetch('/api/license/activate', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(activateData)
        });
        
        const result = await response.json();
        
        if (result.ok) {
            currentLicense = result.license;
            
            // 静默生成license.lic文件到用户本地
            await generateLicenseFile(result.license, hwid);
            
            showResult('licenseResult', {
                success: true,
                message: '许可证激活成功！license.lic已生成到本地',
                plan: result.plan,
                quota: result.quota,
                valid_from: result.valid_from,
                valid_to: result.valid_to,
                server_action: result.server_action,
                server_message: result.server_message,
                license_preview: {
                    alg: result.license.alg,
                    kid: result.license.kid,
                    payload_length: result.license.payload.length,
                    sig_length: result.license.sig.length
                }
            });
        } else {
            throw new Error(result.error || '激活失败');
        }
        
    } catch (error) {
        console.error('激活许可证失败:', error);
        showResult('licenseResult', 'ERROR: ' + error.message, true);
    } finally {
        showLoading(false);
    }
}

// 检查服务器绑定状态
async function checkServerBinding() {
    if (!currentLicense) {
        showResult('statusResult', 'ERROR: 请先激活许可证', true);
        return;
    }
    
    showLoading(true);
    
    try {
        const response = await fetch('/api/admin/server-binding', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                code: currentActivationCode,
                hwid: currentHwid
            })
        });
        
        const result = await response.json();
        
        if (result.ok) {
            showResult('statusResult', {
                success: true,
                message: '🖥️ 服务器绑定状态',
                license_id: result.license_id,
                bound_server: result.bound_server || '未绑定',
                last_switch_time: result.last_switch_time || '从未切换',
                cooldown_remaining: result.cooldown_remaining,
                can_switch: result.can_switch,
                note: result.can_switch ? '可以切换服务器' : `需等待 ${result.cooldown_remaining} 分钟后才能切换`
            });
        } else {
            throw new Error(result.error || '获取服务器绑定状态失败');
        }
        
    } catch (error) {
        console.error('检查服务器绑定失败:', error);
        showResult('statusResult', 'ERROR: ' + error.message, true);
    } finally {
        showLoading(false);
    }
}

// 步骤3: 查看许可证状态
async function checkLicenseStatus() {
    showLoading(true);
    
    try {
        if (!currentLicense) {
            throw new Error('请先激活许可证');
        }
        
        const statusData = {
            license: currentLicense,
            hwid: currentHwid
        };
        
        const response = await fetch('/api/license/status', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(statusData)
        });
        
        const result = await response.json();
        
        if (result.ok) {
            showResult('statusResult', {
                success: true,
                status: result.status,
                valid_from: result.valid_from,
                valid_to: result.valid_to,
                quota: result.quota
            });
        } else {
            throw new Error(result.error || '查询状态失败');
        }
        
    } catch (error) {
        console.error('查询许可证状态失败:', error);
        showResult('statusResult', 'ERROR: ' + error.message, true);
    } finally {
        showLoading(false);
    }
}

// 步骤4: 获取源码列表
async function loadSourceList() {
    showLoading(true);
    
    try {
        const response = await fetch('/api/sources');
        const result = await response.json();
        
        if (result.success && result.data) {
            const sources = result.data;
            
            // 更新下拉选择框
            const select = document.getElementById('sourceSelect');
            select.innerHTML = '<option value="">请选择源码</option>';
            
            sources.forEach(source => {
                const option = document.createElement('option');
                option.value = source.sha256;
                option.textContent = `${source.name} (${source.codeName} v${source.version})`;
                option.dataset.source = JSON.stringify(source);
                select.appendChild(option);
            });
            
            showResult('sourceListResult', {
                success: true,
                message: `找到 ${sources.length} 个源码`,
                sources: sources.map(s => ({
                    name: s.name,
                    codeName: s.codeName,
                    version: s.version,
                    country: s.country,
                    size: formatFileSize(s.fileSize),
                    status: s.status
                }))
            });
        } else {
            showResult('sourceListResult', {
                success: false,
                message: '暂无可用源码，请先在源码管理中上传源码'
            });
        }
        
    } catch (error) {
        console.error('获取源码列表失败:', error);
        showResult('sourceListResult', 'ERROR: ' + error.message, true);
    } finally {
        showLoading(false);
    }
}

// 授权下载源码（消耗授权次数）
async function downloadSourceWithAuth() {
    const select = document.getElementById('sourceSelect');
    const selectedOption = select.options[select.selectedIndex];
    
    if (!selectedOption.value) {
        showResult('downloadResult', 'ERROR: 请选择要下载的源码', true);
        return;
    }
    
    if (!currentLicense) {
        showResult('downloadResult', 'ERROR: 请先激活许可证', true);
        return;
    }
    
    showLoading(true);
    
    try {
        const source = JSON.parse(selectedOption.dataset.source);
        
        showResult('downloadResult', '正在验证许可证并申请下载授权...');
        
        const downloadData = {
            license: currentLicense,
            hwid: currentHwid,
            client: {
                ver: '1.0.0',
                os: 'Windows_Demo'
            }
        };
        
        const response = await fetch(`/api/authorized-download/source/${source.sha256}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(downloadData)
        });
        
        if (response.ok) {
            // 获取下载信息
            const downloadToken = response.headers.get('X-Download-Token');
            const sourceName = response.headers.get('X-Source-Name');
            const sourceVersion = response.headers.get('X-Source-Version');
            
            // 创建下载链接
            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `${sourceName}_${sourceVersion}${source.packageExt || '.zip'}`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            window.URL.revokeObjectURL(url);
            
            showResult('downloadResult', {
                success: true,
                message: '🎉 授权下载成功！',
                source: sourceName,
                version: sourceVersion,
                download_token: downloadToken,
                note: '已消耗1次下载次数，文件已自动下载'
            });
            
            // 更新许可证状态
            setTimeout(() => checkLicenseStatus(), 1000);
            
        } else {
            const errorResult = await response.json();
            throw new Error(errorResult.error || '下载失败');
        }
        
    } catch (error) {
        console.error('授权下载失败:', error);
        showResult('downloadResult', 'ERROR: ' + error.message, true);
    } finally {
        showLoading(false);
    }
}

// 模拟完整的授权下载流程
async function simulateDownload() {
    const select = document.getElementById('sourceSelect');
    const selectedOption = select.options[select.selectedIndex];
    
    if (!selectedOption.value) {
        showResult('downloadResult', 'ERROR: 请选择要下载的源码', true);
        return;
    }
    
    if (!currentLicense) {
        showResult('downloadResult', 'ERROR: 请先激活许可证', true);
        return;
    }
    
    showLoading(true);
    
    try {
        const source = JSON.parse(selectedOption.dataset.source);
        const fileId = source.sha256;
        
        showResult('downloadResult', '步骤1/3: 申请下载授权...');
        
        // 1. 预授权
        const preauthData = {
            license: currentLicense,
            hwid: currentHwid,
            file_id: fileId,
            client: {
                ver: '1.0.0',
                os: 'Windows_Demo'
            }
        };
        
        const preauthResponse = await fetch('/api/download/preauth', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(preauthData)
        });
        
        const preauthResult = await preauthResponse.json();
        
        if (!preauthResult.ok) {
            throw new Error('预授权失败: ' + preauthResult.error);
        }
        
        showResult('downloadResult', '步骤2/3: 模拟下载文件...');
        await sleep(2000); // 模拟下载时间
        
        // 2. 提交下载回执
        const commitData = {
            download_token: preauthResult.download_token,
            result: {
                ok: true,
                size: source.fileSize || 1024000,
                sha256: source.sha256
            },
            client: {
                latency_ms: 2000
            }
        };
        
        const commitResponse = await fetch('/api/download/commit', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(commitData)
        });
        
        const commitResult = await commitResponse.json();
        
        if (!commitResult.ok) {
            throw new Error('提交回执失败: ' + commitResult.error);
        }
        
        showResult('downloadResult', {
            success: true,
            message: '🎉 授权下载完成！',
            source: source.name,
            deducted: commitResult.deducted + ' 次',
            remaining: commitResult.remaining + ' 次剩余',
            download_url: preauthResult.download_url,
            note: '这是授权下载，已消耗下载次数'
        });
        
    } catch (error) {
        console.error('模拟下载失败:', error);
        showResult('downloadResult', 'ERROR: ' + error.message, true);
    } finally {
        showLoading(false);
    }
}

// 重置Demo
function resetDemo() {
    currentLicense = null;
    currentActivationCode = null;
    currentHwid = generateHwid();
    
    document.getElementById('hwid').value = currentHwid;
    document.getElementById('activationCode').value = '';
    document.getElementById('clientId').value = '';
    document.getElementById('sourceSelect').innerHTML = '<option value="">请先获取源码列表</option>';
    
    const resultElements = ['generatedCode', 'licenseResult', 'statusResult', 'sourceListResult', 'downloadResult', 'summaryResult'];
    resultElements.forEach(id => {
        document.getElementById(id).innerHTML = '等待操作...';
    });
    
    showResult('summaryResult', 'Demo已重置，可以开始新的测试');
}

// 自动测试流程
async function autoTest() {
    showResult('summaryResult', '开始自动测试流程...');
    
    try {
        // 步骤1: 生成激活码
        showResult('summaryResult', '步骤1/6: 生成激活码...');
        await generateCode();
        await sleep(1000);
        
        // 步骤2: 激活许可证
        showResult('summaryResult', '步骤2/6: 激活许可证...');
        await activateLicense();
        await sleep(1000);
        
        // 步骤3: 查看状态
        showResult('summaryResult', '步骤3/6: 查看许可证状态...');
        await checkLicenseStatus();
        await sleep(1000);
        
        // 步骤4: 检查服务器绑定
        showResult('summaryResult', '步骤4/6: 检查服务器绑定状态...');
        await checkServerBinding();
        await sleep(1000);
        
        // 步骤5: 获取源码列表
        showResult('summaryResult', '步骤5/6: 获取源码列表...');
        await loadSourceList();
        await sleep(1000);
        
        // 步骤6: 授权下载
        showResult('summaryResult', '步骤6/6: 测试授权下载...');
        
        // 自动选择第一个源码
        const select = document.getElementById('sourceSelect');
        if (select.options.length > 1) {
            select.selectedIndex = 1;
            await downloadSourceWithAuth();
        } else {
            showResult('downloadResult', '没有可用的源码进行下载测试');
        }
        
        showResult('summaryResult', {
            success: true,
            message: '🎉 自动测试流程完成！',
            summary: '所有步骤都已成功执行',
            timestamp: new Date().toLocaleString()
        });
        
    } catch (error) {
        showResult('summaryResult', 'ERROR: 自动测试失败 - ' + error.message, true);
    }
}

// 辅助函数
function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function formatFileSize(bytes) {
    if (!bytes) return '未知';
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return Math.round(bytes / Math.pow(1024, i) * 100) / 100 + ' ' + sizes[i];
}
