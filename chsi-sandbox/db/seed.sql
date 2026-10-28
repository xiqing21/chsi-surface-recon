-- 学信网沙箱模拟中央库
-- 设计约束：
--   1. 上报端只能写 audit_status=pending
--   2. 查询端只读 approved 且 grad_date <= now()
--   3. 已批准的 diploma 记录禁止 UPDATE（触发器拦截）

CREATE DATABASE IF NOT EXISTS chsi CHARACTER SET utf8mb4;
USE chsi;

CREATE TABLE enrollment (
  seq         BIGINT AUTO_INCREMENT PRIMARY KEY,
  student_no  VARCHAR(20)  NOT NULL,
  name        VARCHAR(50)  NOT NULL,
  id_card     VARCHAR(18)  NOT NULL,
  school      VARCHAR(100) NOT NULL,
  major       VARCHAR(100) NOT NULL,
  level       VARCHAR(20)  NOT NULL,
  enroll_date DATE         NOT NULL,
  status      VARCHAR(20)  NOT NULL DEFAULT '在校',
  UNIQUE KEY uk_student (student_no),
  KEY idx_idcard (id_card)
);

CREATE TABLE diploma (
  cert_no      VARCHAR(20)  NOT NULL PRIMARY KEY,
  student_no   VARCHAR(20)  NOT NULL,
  name         VARCHAR(50)  NOT NULL,
  school       VARCHAR(100) NOT NULL,
  major        VARCHAR(100) NOT NULL,
  level        VARCHAR(20)  NOT NULL,
  grad_date    DATE         NOT NULL,
  audit_status VARCHAR(20)  NOT NULL DEFAULT 'pending',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_name (name),
  KEY idx_status (audit_status)
);

CREATE TABLE verify_report (
  report_no   VARCHAR(32)  NOT NULL PRIMARY KEY,
  verify_code CHAR(16)     NOT NULL,
  student_no  VARCHAR(20)  NOT NULL,
  expire_at   DATETIME     NOT NULL,
  KEY idx_code (verify_code)
);

CREATE TABLE query_audit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  rdmid       VARCHAR(64),
  cert_no     VARCHAR(20),
  name        VARCHAR(50),
  ip          VARCHAR(64),
  result      VARCHAR(20),
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 约束 3：已批准记录禁止 UPDATE
DELIMITER $$
CREATE TRIGGER trg_diploma_guard
BEFORE UPDATE ON diploma
FOR EACH ROW
BEGIN
  IF OLD.audit_status = 'approved' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'approved diploma is immutable';
  END IF;
END$$
DELIMITER ;

-- 约束 1：上报端视图，只能看到/写入 pending
CREATE OR REPLACE VIEW v_report_in AS
  SELECT * FROM diploma WHERE audit_status = 'pending';

-- 约束 2：查询端视图，只读 approved 且已到毕业时间
CREATE OR REPLACE VIEW v_query_out AS
  SELECT cert_no, name, school, major, level, grad_date
  FROM diploma
  WHERE audit_status = 'approved' AND grad_date <= CURDATE();

-- 种子数据（合成）
INSERT INTO enrollment (student_no,name,id_card,school,major,level,enroll_date,status) VALUES
 ('2017006058','ZHANG SAN','110101199801011234','示例大学','计算机科学与技术','本科','2017-09-01','毕业'),
 ('2019001122','LI SI',    '310101199905054321','示例大学','法学','本科','2019-09-01','毕业');

INSERT INTO diploma (cert_no,student_no,name,school,major,level,grad_date,audit_status) VALUES
 ('1048712021000001','2017006058','ZHANG SAN','示例大学','计算机科学与技术','本科','2021-06-30','approved'),
 ('1048712023000002','2019001122','LI SI',    '示例大学','法学','本科','2023-06-30','approved'),
 ('1048712025000003','2017006058','WANG WU',  '示例大学','软件工程','本科','2025-06-30','pending');

INSERT INTO verify_report (report_no,verify_code,student_no,expire_at) VALUES
 ('RPT2021000001','ABCDEFGH12345678','2017006058','2027-01-01 00:00:00');
